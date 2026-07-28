package com.timiroom.domain.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.github.dto.ConsistencyFinding;
import com.timiroom.domain.github.dto.EvidenceReference;
import com.timiroom.domain.github.dto.ProjectConsistencySummary;
import com.timiroom.domain.github.dto.ProjectPullRequestResponse;
import com.timiroom.domain.github.dto.PullRequestConsistencyResult;
import com.timiroom.domain.github.dto.RelatedPullRequestResponse;
import com.timiroom.domain.notification.NotificationReferenceType;
import com.timiroom.domain.notification.NotificationService;
import com.timiroom.domain.notification.NotificationType;
import com.timiroom.domain.pipeline.PipelineArtifact;
import com.timiroom.domain.pipeline.PipelineService;
import com.timiroom.domain.project.ProjectMember;
import com.timiroom.domain.project.ProjectMemberRepository;
import com.timiroom.domain.project.ProjectRole;
import com.timiroom.domain.project.ProjectService;
import com.timiroom.infra.github.GithubClient;
import com.timiroom.infra.github.dto.GithubCheckRunInfo;
import com.timiroom.infra.github.dto.GithubPullRequestFileInfo;
import com.timiroom.infra.github.dto.GithubPullRequestInfo;
import com.timiroom.infra.github.dto.GithubPullRequestReviewInfo;
import com.timiroom.infra.consistency.ConsistencyServiceClient;
import com.timiroom.infra.ragpipeline.RagPipelineClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 연결된 레포 PR의 변경 파일을 최신 API_SPEC/DB_SCHEMA와 대조하고 summary review comment를 남긴다.
 * 자동 승인이나 변경 요청은 하지 않으며, 같은 head SHA에는 한 번만 게시한다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PullRequestConsistencyService {

    private static final Pattern API_PATH = Pattern.compile("[\\\"'](/(?:api|v\\d+)[^\\\"'\\s]*)[\\\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_ANNOTATION = Pattern.compile("@Table\\s*\\(\\s*name\\s*=\\s*[\\\"']([^\\\"']+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_SQL = Pattern.compile("(?:create|alter)\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([a-zA-Z0-9_.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISSUE_REFERENCE = Pattern.compile("(?<![A-Za-z0-9_])#(\\d+)");
    private static final Pattern BRANCH_PREFIX = Pattern.compile("^(?:feature|fix|hotfix|refactor|docs|chore)/", Pattern.CASE_INSENSITIVE);
    private static final Pattern AT_TOKEN = Pattern.compile("(?<![A-Za-z0-9_`])@([A-Za-z_][A-Za-z0-9_-]*)");
    private static final Pattern REVIEWABLE_SOURCE = Pattern.compile(
            ".*\\.(?:java|kt|kts|py|js|jsx|ts|tsx|sql|yml|yaml|json)$", Pattern.CASE_INSENSITIVE);
    private static final int MAX_CONTEXT_FILES = 15;

    private final ProjectService projectService;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectRepoLinkRepository projectRepoLinkRepository;
    private final GithubRepoRepository githubRepoRepository;
    private final GithubPullRequestReviewRecordRepository reviewRecordRepository;
    private final PipelineService pipelineService;
    private final NotificationService notificationService;
    private final RagPipelineClient ragPipelineClient;
    private final ConsistencyServiceClient consistencyServiceClient;
    private final ObjectMapper objectMapper;
    private final GithubClient githubClient;

    @Value("${github.consistency.agent-enabled:true}")
    private boolean agentEnabled;

    @Value("${github.consistency.agent-model:gpt-5.4-mini}")
    private String agentModel;

    @Value("${github.consistency.runtime:PYTHON}")
    private String agentRuntime;

    @Transactional(readOnly = true)
    public List<ProjectPullRequestResponse> list(Long projectId, Long memberId) {
        projectService.getById(projectId, memberId);
        List<PullWithRepo> pulls = projectRepoLinkRepository.findByProjectId(projectId).stream()
                .map(link -> githubRepoRepository.findById(link.getGithubRepoId()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .flatMap(repo -> githubClient.listPullRequests(repo.getFullName(), repo.getInstallationId()).stream()
                        .map(pr -> new PullWithRepo(repo, pr)))
                .sorted(Comparator.comparing(item -> item.pullRequest().updatedAt(),
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        Map<String, GithubPullRequestReviewRecord> records = pulls.isEmpty()
                ? Map.of()
                : reviewRecordRepository.findByProjectIdAndGithubRepoIdIn(projectId,
                                pulls.stream().map(item -> item.repo().getId()).distinct().toList())
                        .stream().collect(java.util.stream.Collectors.toMap(
                                record -> reviewKey(record.getGithubRepoId(), record.getPullNumber()),
                                record -> record,
                                (left, right) -> left));
        return pulls.stream().map(pull -> toResponse(pull.repo(), pull.pullRequest(),
                relatedPullRequests(pull, pulls), records)).toList();
    }

    @Transactional
    public PullRequestConsistencyResult checkAndReview(Long projectId, Long memberId, Long repoId, int pullNumber) {
        GithubRepo repo = findLinkedRepo(projectId, memberId, repoId);
        requirePm(projectId, memberId);
        return checkAndReview(projectId, repo, pullNumber);
    }

    /** 검증된 GitHub webhook 경로에서 호출한다. */
    @Transactional
    public PullRequestConsistencyResult checkAndReviewFromWebhook(Long projectId, Long repoId, int pullNumber) {
        GithubRepo repo = findLinkedRepoWithoutMember(projectId, repoId);
        return checkAndReview(projectId, repo, pullNumber);
    }

    private PullRequestConsistencyResult checkAndReview(Long projectId, GithubRepo repo, int pullNumber) {
        GithubPullRequestInfo pullRequest = githubClient.getPullRequest(repo.getFullName(), repo.getInstallationId(), pullNumber);
        GithubPullRequestReviewRecord existing = reviewRecordRepository
                .findByProjectIdAndGithubRepoIdAndPullNumber(projectId, repo.getId(), pullNumber).orElse(null);
        if (existing != null && pullRequest.headSha() != null && pullRequest.headSha().equals(existing.getHeadSha())) {
            return new PullRequestConsistencyResult(repo.getId(), pullNumber, pullRequest.headSha(),
                    existing.getScore() != null ? existing.getScore() : 0,
                    false, true, existing.getReviewUrl(), existing.getCheckRunUrl(),
                    existing.getEvaluator() != null ? existing.getEvaluator() : "CACHED",
                    readFindings(existing.getFindingsJson()));
        }

        List<GithubPullRequestFileInfo> files = withFullFileContext(repo, pullRequest,
                githubClient.listPullRequestFiles(repo.getFullName(), repo.getInstallationId(), pullNumber));
        Map<PipelineArtifact.ArtifactType, String> specifications = latestSpecifications(projectId);
        AnalysisOutcome analysis = analyzeWithAgent(repo, pullRequest, files, specifications);
        List<ConsistencyFinding> findings = analysis.findings();
        long warningCount = findings.stream().filter(f -> "WARNING".equals(f.severity())).count();
        long inconclusiveCount = findings.stream().filter(f -> "INCONCLUSIVE".equals(f.severity())).count();
        long attentionCount = warningCount + inconclusiveCount;
        int score = inconclusiveCount > 0 ? 0 : Math.max(0, 100 - (int) warningCount * 25);
        String findingsJson = writeFindings(findings);

        String reviewBody = reviewMarkdown(score, findings, analysis.evaluator(), analysis.summary(), pullRequest);
        GithubCheckRunInfo checkRun = githubClient.createCompletedConsistencyCheckRun(repo.getFullName(),
                repo.getInstallationId(), pullRequest.headSha(), score, inconclusiveCount > 0, reviewBody);
        GithubPullRequestReviewInfo review = githubClient.createPullRequestCommentReview(repo.getFullName(),
                repo.getInstallationId(), pullNumber, pullRequest.headSha(), reviewBody);
        if (existing == null) {
            reviewRecordRepository.save(GithubPullRequestReviewRecord.builder()
                    .projectId(projectId).githubRepoId(repo.getId()).pullNumber(pullNumber)
                    .headSha(pullRequest.headSha()).reviewUrl(review.htmlUrl()).checkRunUrl(checkRun.htmlUrl())
                    .score(score).findingsJson(findingsJson).evaluator(analysis.evaluator()).build());
        } else {
            existing.updateReview(pullRequest.headSha(), review.htmlUrl(), checkRun.htmlUrl());
            existing.updateResult(score, findingsJson, analysis.evaluator());
        }
        if (attentionCount > 0) notifyProjectMembers(projectId, repo, pullNumber, warningCount, inconclusiveCount);
        return new PullRequestConsistencyResult(repo.getId(), pullNumber, pullRequest.headSha(), score,
                true, false, review.htmlUrl(), checkRun.htmlUrl(), analysis.evaluator(), findings);
    }

    /** 프로젝트 전체에서 가장 최근에 검사된 PR의 정합성 요약. 아직 검사한 PR이 없으면 null. */
    @Transactional(readOnly = true)
    public ProjectConsistencySummary getLatestSummary(Long projectId, Long memberId) {
        projectService.getById(projectId, memberId);
        List<Long> linkedRepoIds = projectRepoLinkRepository.findByProjectId(projectId).stream()
                .map(ProjectRepoLink::getGithubRepoId)
                .toList();
        if (linkedRepoIds.isEmpty()) return null;
        GithubPullRequestReviewRecord record = reviewRecordRepository
                .findFirstByProjectIdAndGithubRepoIdInOrderByUpdatedAtDesc(projectId, linkedRepoIds).orElse(null);
        if (record == null || record.getFindingsJson() == null) return null;
        GithubRepo repo = githubRepoRepository.findById(record.getGithubRepoId()).orElse(null);
        if (repo == null) return null;
        return new ProjectConsistencySummary(repo.getId(), repo.getFullName(), record.getPullNumber(),
                record.getScore() != null ? record.getScore() : 0, record.getReviewUrl(), record.getCheckRunUrl(),
                record.getUpdatedAt(), record.getEvaluator() != null ? record.getEvaluator() : "CACHED",
                readFindings(record.getFindingsJson()));
    }

    private String writeFindings(List<ConsistencyFinding> findings) {
        try {
            return objectMapper.writeValueAsString(findings);
        } catch (Exception e) {
            return null;
        }
    }

    private List<ConsistencyFinding> readFindings(String findingsJson) {
        try {
            return objectMapper.readValue(findingsJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ConsistencyFinding.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private void notifyProjectMembers(Long projectId, GithubRepo repo, int pullNumber,
                                      long warningCount, long inconclusiveCount) {
        String title = "PR 정합성 확인 필요";
        String detail = warningCount > 0 ? warningCount + "개의 명세 정합성 경고"
                : "근거 부족으로 인한 판정 보류 " + inconclusiveCount + "건";
        String content = repo.getFullName() + " #" + pullNumber + "에서 " + detail + "이 발견됐습니다.";
        projectMemberRepository.findByProjectId(projectId).forEach(member -> notificationService.create(
                member.getMemberId(), NotificationType.PR_CONSISTENCY_REVIEW, title, content,
                NotificationReferenceType.PULL_REQUEST, (long) pullNumber));
    }

    private Map<PipelineArtifact.ArtifactType, String> latestSpecifications(Long projectId) {
        Map<PipelineArtifact.ArtifactType, String> result = new LinkedHashMap<>();
        pipelineService.getLatestArtifactsByProject(projectId).forEach(artifact -> result.put(artifact.getArtifactType(), artifact.getContent()));
        return result;
    }

    private List<GithubPullRequestFileInfo> withFullFileContext(GithubRepo repo,
                                                                GithubPullRequestInfo pullRequest,
                                                                List<GithubPullRequestFileInfo> files) {
        int[] fetched = {0};
        return files.stream().map(file -> {
            if (fetched[0] >= MAX_CONTEXT_FILES || !REVIEWABLE_SOURCE.matcher(file.filename()).matches()) {
                return file;
            }
            fetched[0]++;
            String content = "removed".equalsIgnoreCase(file.status()) ? null
                    : githubClient.getRepositoryFileContent(repo.getFullName(), repo.getInstallationId(),
                            file.filename(), pullRequest.headSha()).orElse(null);
            String baseContent = "added".equalsIgnoreCase(file.status()) ? null
                    : githubClient.getRepositoryFileContent(repo.getFullName(), repo.getInstallationId(),
                            file.filename(), pullRequest.baseRef()).orElse(null);
            return file.withContents(content, baseContent);
        }).toList();
    }

    private AnalysisOutcome analyzeWithAgent(GithubRepo repo,
                                             GithubPullRequestInfo pullRequest,
                                             List<GithubPullRequestFileInfo> files,
                                             Map<PipelineArtifact.ArtifactType, String> specifications) {
        if (!agentEnabled) {
            List<ConsistencyFinding> findings = analyzeWithRules(files, specifications);
            return new AnalysisOutcome(findings, "RULES", defaultReviewSummary(findings));
        }
        try {
            String runtime = normalizedAgentRuntime();
            Object request = agentRequest(repo, pullRequest, files, specifications);
            JsonNode response = "PYTHON".equals(runtime)
                    ? consistencyServiceClient.reviewPullRequestConsistency(request)
                    : ragPipelineClient.reviewPullRequestConsistency(request);
            List<ConsistencyFinding> findings = new ArrayList<>();
            for (JsonNode finding : response.path("findings")) {
                String severity = finding.path("severity").asText("INFO").toUpperCase();
                if (!Set.of("PASS", "INFO", "WARNING", "INCONCLUSIVE").contains(severity)) severity = "INFO";
                String area = finding.path("area").asText("Agent").trim();
                String message = finding.path("message").asText("").trim();
                List<String> evidence = new ArrayList<>();
                JsonNode evidenceNode = finding.path("evidence");
                if (evidenceNode.isArray()) {
                    for (JsonNode item : evidenceNode) {
                        String value = item.asText("").trim();
                        if (!value.isBlank() && evidence.size() < 4) evidence.add(value);
                    }
                } else if (evidenceNode.isTextual() && !evidenceNode.asText().isBlank()) {
                    evidence.add(evidenceNode.asText().trim());
                }
                String recommendation = finding.path("recommendation").asText("").trim();
                List<EvidenceReference> references = new ArrayList<>();
                if (finding.path("references").isArray()) {
                    for (JsonNode reference : finding.path("references")) {
                        String sourceType = reference.path("sourceType").asText("").trim();
                        String source = reference.path("source").asText("").trim();
                        String quote = reference.path("quote").asText("").trim();
                        Integer line = reference.path("line").canConvertToInt() ? reference.path("line").asInt() : null;
                        if (!sourceType.isBlank() && !source.isBlank() && !quote.isBlank() && references.size() < 4) {
                            references.add(new EvidenceReference(sourceType, source, line, quote));
                        }
                    }
                }
                if (!message.isBlank()) findings.add(new ConsistencyFinding(severity, area, message,
                        List.copyOf(evidence), List.copyOf(references),
                        recommendation.isBlank() ? null : recommendation));
            }
            if (findings.isEmpty()) {
                throw new IllegalStateException("Consistency Agent가 finding을 반환하지 않았습니다");
            }
            String evaluator = "SPRING".equals(runtime)
                    ? "SPRING_FOUNDRY"
                    : "EXAONE_FACT_GATE".equals(response.path("evaluationMode").asText())
                            ? "PYTHON_EXAONE_FACT_GATE"
                            : "PYTHON_EXAONE";
            return new AnalysisOutcome(List.copyOf(findings), evaluator,
                    response.path("summary").asText(defaultReviewSummary(findings)).trim());
        } catch (Exception e) {
            log.warn("PR Consistency Agent 실패 — 규칙 엔진 fallback: {}", e.getMessage());
            List<ConsistencyFinding> findings = new ArrayList<>(analyzeWithRules(files, specifications));
            findings.add(new ConsistencyFinding("INFO", "검사기",
                    "Consistency Agent를 사용할 수 없어 규칙 기반 검사 결과를 사용했습니다."));
            return new AnalysisOutcome(List.copyOf(findings), "RULE_FALLBACK", defaultReviewSummary(findings));
        }
    }

    private Map<String, Object> agentRequest(GithubRepo repo,
                                             GithubPullRequestInfo pullRequest,
                                             List<GithubPullRequestFileInfo> files,
                                             Map<PipelineArtifact.ArtifactType, String> specifications) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", agentModel);
        request.put("repository", repo.getFullName());
        request.put("pullNumber", pullRequest.number());
        request.put("title", pullRequest.title());
        request.put("body", pullRequest.body());
        request.put("headRef", pullRequest.headRef());
        request.put("baseRef", pullRequest.baseRef());
        request.put("apiSpec", specifications.get(PipelineArtifact.ArtifactType.API_SPEC));
        request.put("dbSchema", specifications.get(PipelineArtifact.ArtifactType.DB_SCHEMA));
        request.put("changedFiles", files.stream().map(file -> {
            Map<String, Object> changedFile = new LinkedHashMap<>();
            changedFile.put("filename", file.filename());
            changedFile.put("status", file.status());
            changedFile.put("additions", file.additions());
            changedFile.put("deletions", file.deletions());
            changedFile.put("patch", file.patch());
            changedFile.put("content", file.content());
            changedFile.put("baseContent", file.baseContent());
            changedFile.put("patchTruncated", file.patchTruncated());
            return changedFile;
        }).toList());
        return request;
    }

    private List<ConsistencyFinding> analyzeWithRules(List<GithubPullRequestFileInfo> files,
                                                      Map<PipelineArtifact.ArtifactType, String> specifications) {
        List<ConsistencyFinding> findings = new ArrayList<>();
        boolean apiChanged = files.stream().anyMatch(this::looksLikeApiChange);
        boolean dbChanged = files.stream().anyMatch(this::looksLikeDatabaseChange);

        if (apiChanged) analyzeApi(files, specifications.get(PipelineArtifact.ArtifactType.API_SPEC), findings);
        else findings.add(new ConsistencyFinding("INFO", "API", "API 엔드포인트 변경 패턴이 감지되지 않았습니다."));

        if (dbChanged) analyzeDatabase(files, specifications.get(PipelineArtifact.ArtifactType.DB_SCHEMA), findings);
        else findings.add(new ConsistencyFinding("INFO", "DB", "엔티티·마이그레이션 변경 패턴이 감지되지 않았습니다."));

        if (files.isEmpty()) findings.add(new ConsistencyFinding("INFO", "변경 파일", "GitHub에서 변경 파일을 받지 못해 파일 기반 검사를 건너뛰었습니다."));
        return findings;
    }

    private void analyzeApi(List<GithubPullRequestFileInfo> files, String apiSpec, List<ConsistencyFinding> findings) {
        if (apiSpec == null || apiSpec.isBlank()) {
            findings.add(new ConsistencyFinding("WARNING", "API 명세", "API 변경이 감지됐지만 최신 API_SPEC 산출물이 없어 대조하지 못했습니다."));
            return;
        }
        Set<String> endpoints = new java.util.LinkedHashSet<>();
        files.forEach(file -> endpoints.addAll(extract(API_PATH, file.patch())));
        if (endpoints.isEmpty()) {
            findings.add(new ConsistencyFinding("INFO", "API 명세", "API 관련 파일이 변경됐지만 diff에서 비교 가능한 endpoint 문자열을 찾지 못했습니다."));
            return;
        }
        String normalizedSpec = apiSpec.toLowerCase();
        for (String endpoint : endpoints) {
            if (normalizedSpec.contains(endpoint.toLowerCase())) {
                findings.add(new ConsistencyFinding("PASS", "API 명세", endpoint + "가 API_SPEC에 있습니다."));
            } else {
                findings.add(new ConsistencyFinding("WARNING", "API 명세", endpoint + "를 API_SPEC에서 찾지 못했습니다. 명세 또는 구현을 확인하세요."));
            }
        }
    }

    private void analyzeDatabase(List<GithubPullRequestFileInfo> files, String dbSchema, List<ConsistencyFinding> findings) {
        if (dbSchema == null || dbSchema.isBlank()) {
            findings.add(new ConsistencyFinding("WARNING", "DB 스키마", "DB 변경이 감지됐지만 최신 DB_SCHEMA 산출물이 없어 대조하지 못했습니다."));
            return;
        }
        Set<String> tables = new java.util.LinkedHashSet<>();
        files.forEach(file -> {
            tables.addAll(extract(TABLE_ANNOTATION, file.patch()));
            tables.addAll(extract(TABLE_SQL, file.patch()));
        });
        if (tables.isEmpty()) {
            findings.add(new ConsistencyFinding("INFO", "DB 스키마", "DB 관련 파일이 변경됐지만 diff에서 비교 가능한 테이블 이름을 찾지 못했습니다."));
            return;
        }
        String normalizedSchema = dbSchema.toLowerCase();
        for (String table : tables) {
            if (normalizedSchema.contains(table.toLowerCase())) {
                findings.add(new ConsistencyFinding("PASS", "DB 스키마", table + " 테이블이 DB_SCHEMA에 있습니다."));
            } else {
                findings.add(new ConsistencyFinding("WARNING", "DB 스키마", table + " 테이블을 DB_SCHEMA에서 찾지 못했습니다. 스키마 또는 구현을 확인하세요."));
            }
        }
    }

    private boolean looksLikeApiChange(GithubPullRequestFileInfo file) {
        String text = (file.filename() + "\n" + file.patch()).toLowerCase();
        return text.contains("controller") || text.contains("@requestmapping") || text.contains("@getmapping")
                || text.contains("@postmapping") || text.contains("/api/") || text.contains("router.");
    }

    private boolean looksLikeDatabaseChange(GithubPullRequestFileInfo file) {
        String text = (file.filename() + "\n" + file.patch()).toLowerCase();
        return text.contains("migration") || text.contains("@entity") || text.contains("@table")
                || text.contains("create table") || text.contains("alter table") || text.contains("@column");
    }

    private Set<String> extract(Pattern pattern, String text) {
        Set<String> values = new java.util.LinkedHashSet<>();
        if (text == null) return values;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) values.add(matcher.group(1));
        return values;
    }

    private List<RelatedPullRequestResponse> relatedPullRequests(PullWithRepo source, List<PullWithRepo> allPullRequests) {
        Set<String> sourceKeys = correlationKeys(source.pullRequest());
        if (sourceKeys.isEmpty()) return List.of();
        return allPullRequests.stream()
                .filter(candidate -> candidate != source)
                .filter(candidate -> !candidate.repo().getId().equals(source.repo().getId()))
                .filter(candidate -> candidate.pullRequest().number() != source.pullRequest().number())
                .filter(candidate -> correlationKeys(candidate.pullRequest()).stream().anyMatch(sourceKeys::contains))
                .map(candidate -> new RelatedPullRequestResponse(candidate.repo().getId(), candidate.repo().getFullName(),
                        candidate.pullRequest().number(), candidate.pullRequest().title(), candidate.pullRequest().htmlUrl()))
                .toList();
    }

    private Set<String> correlationKeys(GithubPullRequestInfo pullRequest) {
        Set<String> keys = new java.util.LinkedHashSet<>();
        String titleAndBody = (pullRequest.title() == null ? "" : pullRequest.title()) + "\n"
                + (pullRequest.body() == null ? "" : pullRequest.body());
        Matcher issueMatcher = ISSUE_REFERENCE.matcher(titleAndBody);
        while (issueMatcher.find()) keys.add("issue:" + issueMatcher.group(1));

        String branch = pullRequest.headRef();
        if (branch != null) {
            String normalized = BRANCH_PREFIX.matcher(branch.trim()).replaceFirst("").toLowerCase();
            if (normalized.length() >= 4 && !normalized.equals("main") && !normalized.equals("develop")) {
                keys.add("branch:" + normalized);
            }
        }
        return keys;
    }

    private String reviewMarkdown(int score,
                                  List<ConsistencyFinding> findings,
                                  String evaluator,
                                  String summary,
                                  GithubPullRequestInfo pullRequest) {
        List<ConsistencyFinding> warnings = findings.stream()
                .filter(finding -> "WARNING".equals(finding.severity())).toList();
        List<ConsistencyFinding> passes = findings.stream()
                .filter(finding -> "PASS".equals(finding.severity())).toList();
        List<ConsistencyFinding> information = findings.stream()
                .filter(finding -> !Set.of("WARNING", "PASS", "INCONCLUSIVE").contains(finding.severity())).toList();
        List<ConsistencyFinding> inconclusive = findings.stream()
                .filter(finding -> "INCONCLUSIVE".equals(finding.severity())).toList();
        boolean needsAttention = !warnings.isEmpty() || !inconclusive.isEmpty();

        StringBuilder body = new StringBuilder("## 🔍 timiroom PR 정합성 리뷰\n\n")
                .append(needsAttention ? "> [!WARNING]\n" : "> [!NOTE]\n")
                .append("> ")
                .append(needsAttention
                        ? (!warnings.isEmpty()
                                ? "병합 전에 확인할 정합성 경고가 **" + warnings.size() + "건** 있습니다."
                                : "확정 근거가 부족해 정합성 판정을 보류했습니다.")
                        : "명세와 구현 사이에서 병합을 막을 정합성 문제를 찾지 못했습니다.")
                .append("\n\n")
                .append("| 항목 | 결과 |\n| --- | --- |\n")
                .append("| 상태 | ").append(!inconclusive.isEmpty() ? "❔ 판정 보류" : needsAttention ? "⚠️ 검토 필요" : "✅ 통과").append(" |\n")
                .append("| 정합성 점수 | ").append(!inconclusive.isEmpty() ? "**—**" : "**" + score + "/100**").append(" |\n")
                .append("| 리뷰 엔진 | ").append(evaluatorLabel(evaluator)).append(" |\n")
                .append("| 비교 범위 | 최신 API·DB 명세 ↔ PR 변경 원문·diff |\n")
                .append("| 브랜치 | `").append(tableText(pullRequest.headRef())).append("` → `")
                .append(tableText(pullRequest.baseRef())).append("` |\n\n")
                .append("### 리뷰 요약\n\n")
                .append("> ").append(markdownText(summary == null || summary.isBlank()
                        ? defaultReviewSummary(findings) : summary)).append("\n\n");

        if (!warnings.isEmpty()) {
            body.append("### ⚠️ 병합 전 확인 사항\n\n");
            warnings.forEach(finding -> appendFindingDetails(body, finding, true));
        }
        if (!passes.isEmpty()) {
            body.append("### ✅ 확인된 항목\n\n");
            passes.forEach(finding -> body.append("- **").append(markdownText(finding.area()))
                    .append("** — ").append(markdownText(finding.message())).append("\n"));
            body.append("\n");
        }
        if (!inconclusive.isEmpty()) {
            body.append("### ❔ 판정 보류\n\n");
            inconclusive.forEach(finding -> appendFindingDetails(body, finding, true));
        }
        if (!information.isEmpty()) {
            body.append("<details>\n<summary><strong>ℹ️ 참고 사항 ")
                    .append(information.size()).append("건</strong></summary>\n\n");
            information.forEach(finding -> body.append("- **").append(markdownText(finding.area()))
                    .append("** — ").append(markdownText(finding.message())).append("\n"));
            body.append("\n</details>\n\n");
        }

        body.append("---\n")
                .append("<sub>").append(evaluatorDescription(evaluator))
                .append(" 자동 승인이나 변경 요청은 하지 않습니다. 동일한 head SHA (`")
                .append(shortSha(pullRequest.headSha()))
                .append("`)에는 리뷰를 한 번만 게시합니다.</sub>");
        return body.toString();
    }

    private void appendFindingDetails(StringBuilder body, ConsistencyFinding finding, boolean open) {
        body.append("<details").append(open ? " open" : "").append(">\n")
                .append("<summary><strong>").append(htmlText(finding.area())).append("</strong> — ")
                .append(htmlText(finding.message())).append("</summary>\n\n");
        if (!finding.evidence().isEmpty()) {
            body.append("**근거**\n\n");
            finding.evidence().forEach(evidence -> body.append("- ").append(markdownText(evidence)).append("\n"));
            body.append("\n");
        }
        if (!finding.references().isEmpty()) {
            body.append("**원문 위치**\n\n");
            finding.references().forEach(reference -> body.append("- `")
                    .append(markdownText(reference.source()))
                    .append(reference.line() == null ? "" : ":" + reference.line())
                    .append("` — ").append(markdownText(reference.quote())).append("\n"));
            body.append("\n");
        }
        if (finding.recommendation() != null && !finding.recommendation().isBlank()) {
            body.append("**권장 수정**\n\n")
                    .append(markdownText(finding.recommendation())).append("\n\n");
        }
        body.append("</details>\n\n");
    }

    private String evaluatorLabel(String evaluator) {
        return switch (evaluator) {
            case "PYTHON_EXAONE" -> "🇰🇷 LG EXAONE Agent";
            case "PYTHON_EXAONE_FACT_GATE" -> "🇰🇷 LG EXAONE + Fact Gate";
            case "SPRING_FOUNDRY" -> "🌐 Foundry Agent";
            case "AGENT" -> "AI Consistency Agent";
            case "RULE_FALLBACK" -> "규칙 엔진 (Agent fallback)";
            default -> "규칙 엔진";
        };
    }

    private String evaluatorDescription(String evaluator) {
        return switch (evaluator) {
            case "PYTHON_EXAONE" -> "독립 Python PR Consistency Agent가 Friendli의 LG K-EXAONE으로 검토했습니다.";
            case "PYTHON_EXAONE_FACT_GATE" -> "독립 Python Agent가 LG K-EXAONE 판단을 AST·SQL Fact Gate 원문 근거로 검증했습니다.";
            case "SPRING_FOUNDRY" -> "Spring PR Consistency Agent가 해외 Foundry 모델로 검토했습니다.";
            case "AGENT" -> "전용 PR Consistency Agent가 의미 단위로 검토했습니다.";
            case "RULE_FALLBACK" -> "Agent 호출에 실패해 규칙 엔진으로 검토했습니다.";
            default -> "규칙 엔진으로 검토했습니다.";
        };
    }

    private String defaultReviewSummary(List<ConsistencyFinding> findings) {
        long warnings = findings.stream().filter(finding -> "WARNING".equals(finding.severity())).count();
        if (warnings > 0) return "명세와 구현 사이에서 병합 전 확인이 필요한 항목 " + warnings + "건을 발견했습니다.";
        if (findings.stream().anyMatch(finding -> "INCONCLUSIVE".equals(finding.severity()))) {
            return "확정 가능한 양쪽 근거가 부족해 정합성 판정을 보류했습니다.";
        }
        return "현재 PR 변경 범위에서 명세와 구현 사이의 주요 정합성 문제를 발견하지 못했습니다.";
    }

    private String oneLine(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private String tableText(String value) {
        return oneLine(value).replace("|", "\\|").replace("`", "'");
    }

    private String markdownText(String value) {
        return AT_TOKEN.matcher(oneLine(value)).replaceAll("`@$1`");
    }

    private String htmlText(String value) {
        String escaped = oneLine(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return AT_TOKEN.matcher(escaped).replaceAll("<code>@$1</code>");
    }

    private String shortSha(String sha) {
        if (sha == null || sha.isBlank()) return "unknown";
        return sha.substring(0, Math.min(7, sha.length()));
    }

    private GithubRepo findLinkedRepo(Long projectId, Long memberId, Long repoId) {
        projectService.getById(projectId, memberId);
        return findLinkedRepoWithoutMember(projectId, repoId);
    }

    private GithubRepo findLinkedRepoWithoutMember(Long projectId, Long repoId) {
        projectRepoLinkRepository.findByProjectIdAndGithubRepoId(projectId, repoId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트에 연결된 레포가 아닙니다: " + repoId));
        return githubRepoRepository.findById(repoId)
                .orElseThrow(() -> new IllegalArgumentException("GitHub 레포를 찾을 수 없습니다: " + repoId));
    }

    private void requirePm(Long projectId, Long memberId) {
        ProjectMember member = projectMemberRepository.findByProjectIdAndMemberId(projectId, memberId)
                .orElseThrow(() -> new SecurityException("PR 정합성 리뷰를 남길 프로젝트 권한이 없습니다"));
        if (member.getProjectRole() != ProjectRole.PM) {
            throw new SecurityException("PR 정합성 리뷰를 남기는 작업은 PM만 할 수 있습니다");
        }
    }

    private ProjectPullRequestResponse toResponse(GithubRepo repo, GithubPullRequestInfo pullRequest,
                                                  List<RelatedPullRequestResponse> relatedPullRequests,
                                                  Map<String, GithubPullRequestReviewRecord> records) {
        GithubPullRequestReviewRecord cachedRecord = records.get(reviewKey(repo.getId(), pullRequest.number()));
        PullRequestConsistencyResult consistencyResult = Optional.ofNullable(cachedRecord)
                .filter(record -> pullRequest.headSha() != null && pullRequest.headSha().equals(record.getHeadSha()))
                .map(record -> new PullRequestConsistencyResult(repo.getId(), pullRequest.number(), pullRequest.headSha(),
                        record.getScore() != null ? record.getScore() : 0, false, true,
                        record.getReviewUrl(), record.getCheckRunUrl(),
                        record.getEvaluator() != null ? record.getEvaluator() : "CACHED",
                        readFindings(record.getFindingsJson())))
                .orElse(null);
        return new ProjectPullRequestResponse(repo.getId(), repo.getFullName(), pullRequest.number(), pullRequest.title(),
                pullRequest.body(), pullRequest.state(), pullRequest.draft(), pullRequest.headSha(), pullRequest.headRef(),
                pullRequest.baseRef(), pullRequest.htmlUrl(), pullRequest.authorLogin(), pullRequest.updatedAt(),
                relatedPullRequests, consistencyResult);
    }

    private String reviewKey(Long repoId, Integer pullNumber) {
        return repoId + ":" + pullNumber;
    }

    private record AnalysisOutcome(List<ConsistencyFinding> findings, String evaluator, String summary) {}

    private String normalizedAgentRuntime() {
        String normalized = agentRuntime == null ? "" : agentRuntime.trim().toUpperCase();
        if (!Set.of("PYTHON", "SPRING").contains(normalized)) {
            throw new IllegalArgumentException("지원하지 않는 GITHUB_CONSISTENCY_RUNTIME: " + agentRuntime);
        }
        return normalized;
    }

    private record PullWithRepo(GithubRepo repo, GithubPullRequestInfo pullRequest) {}
}

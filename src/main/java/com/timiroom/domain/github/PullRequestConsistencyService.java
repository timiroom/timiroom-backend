package com.timiroom.domain.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.github.dto.ConsistencyFinding;
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
import com.timiroom.infra.ragpipeline.RagPipelineClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 연결된 레포 PR의 변경 파일을 최신 API_SPEC/DB_SCHEMA와 대조하고 summary review comment를 남긴다.
 * 자동 승인이나 변경 요청은 하지 않으며, 같은 head SHA에는 한 번만 게시한다.
 */
@Service
@RequiredArgsConstructor
public class PullRequestConsistencyService {

    private static final Pattern API_PATH = Pattern.compile("[\\\"'](/(?:api|v\\d+)[^\\\"'\\s]*)[\\\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_ANNOTATION = Pattern.compile("@Table\\s*\\(\\s*name\\s*=\\s*[\\\"']([^\\\"']+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_SQL = Pattern.compile("(?:create|alter)\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([a-zA-Z0-9_.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISSUE_REFERENCE = Pattern.compile("(?<![A-Za-z0-9_])#(\\d+)");
    private static final Pattern BRANCH_PREFIX = Pattern.compile("^(?:feature|fix|hotfix|refactor|docs|chore)/", Pattern.CASE_INSENSITIVE);

    private final ProjectService projectService;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectRepoLinkRepository projectRepoLinkRepository;
    private final GithubRepoRepository githubRepoRepository;
    private final GithubPullRequestReviewRecordRepository reviewRecordRepository;
    private final PipelineService pipelineService;
    private final NotificationService notificationService;
    private final RagPipelineClient ragPipelineClient;
    private final ObjectMapper objectMapper;
    private final GithubClient githubClient;

    @Value("${github.consistency.llm-enabled:false}")
    private boolean llmEnabled;

    @Value("${github.consistency.llm-model:gpt-5.4-mini}")
    private String llmModel;

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
        return pulls.stream().map(pull -> toResponse(pull.repo(), pull.pullRequest(), relatedPullRequests(pull, pulls))).toList();
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
        List<GithubPullRequestFileInfo> files = githubClient.listPullRequestFiles(repo.getFullName(), repo.getInstallationId(), pullNumber);
        Map<PipelineArtifact.ArtifactType, String> specifications = latestSpecifications(projectId);
        List<ConsistencyFinding> findings = analyze(files, specifications);
        appendLlmFindings(files, specifications, findings);
        long warningCount = findings.stream().filter(f -> "WARNING".equals(f.severity())).count();
        int score = Math.max(0, 100 - (int) warningCount * 25);
        String findingsJson = writeFindings(findings);

        GithubPullRequestReviewRecord existing = reviewRecordRepository
                .findByProjectIdAndGithubRepoIdAndPullNumber(projectId, repo.getId(), pullNumber).orElse(null);
        if (existing != null && pullRequest.headSha() != null && pullRequest.headSha().equals(existing.getHeadSha())) {
            existing.updateResult(score, findingsJson);
            return new PullRequestConsistencyResult(repo.getId(), pullNumber, pullRequest.headSha(), score,
                    false, true, existing.getReviewUrl(), existing.getCheckRunUrl(), findings);
        }

        String reviewBody = reviewMarkdown(score, findings);
        GithubCheckRunInfo checkRun = githubClient.createCompletedConsistencyCheckRun(repo.getFullName(),
                repo.getInstallationId(), pullRequest.headSha(), score, warningCount > 0, reviewBody);
        GithubPullRequestReviewInfo review = githubClient.createPullRequestCommentReview(repo.getFullName(),
                repo.getInstallationId(), pullNumber, pullRequest.headSha(), reviewBody);
        if (existing == null) {
            reviewRecordRepository.save(GithubPullRequestReviewRecord.builder()
                    .projectId(projectId).githubRepoId(repo.getId()).pullNumber(pullNumber)
                    .headSha(pullRequest.headSha()).reviewUrl(review.htmlUrl()).checkRunUrl(checkRun.htmlUrl())
                    .score(score).findingsJson(findingsJson).build());
        } else {
            existing.updateReview(pullRequest.headSha(), review.htmlUrl(), checkRun.htmlUrl());
            existing.updateResult(score, findingsJson);
        }
        if (warningCount > 0) notifyProjectMembers(projectId, repo, pullNumber, warningCount);
        return new PullRequestConsistencyResult(repo.getId(), pullNumber, pullRequest.headSha(), score,
                true, false, review.htmlUrl(), checkRun.htmlUrl(), findings);
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
                record.getUpdatedAt(), readFindings(record.getFindingsJson()));
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

    private void notifyProjectMembers(Long projectId, GithubRepo repo, int pullNumber, long warningCount) {
        String title = "PR 정합성 확인 필요";
        String content = repo.getFullName() + " #" + pullNumber + "에서 " + warningCount
                + "개의 명세 정합성 경고가 발견됐습니다.";
        projectMemberRepository.findByProjectId(projectId).forEach(member -> notificationService.create(
                member.getMemberId(), NotificationType.PR_CONSISTENCY_REVIEW, title, content,
                NotificationReferenceType.PULL_REQUEST, (long) pullNumber));
    }

    private Map<PipelineArtifact.ArtifactType, String> latestSpecifications(Long projectId) {
        Map<PipelineArtifact.ArtifactType, String> result = new LinkedHashMap<>();
        pipelineService.getLatestArtifactsByProject(projectId).forEach(artifact -> result.put(artifact.getArtifactType(), artifact.getContent()));
        return result;
    }

    private List<ConsistencyFinding> analyze(List<GithubPullRequestFileInfo> files,
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

    private void appendLlmFindings(List<GithubPullRequestFileInfo> files,
                                   Map<PipelineArtifact.ArtifactType, String> specifications,
                                   List<ConsistencyFinding> findings) {
        if (!llmEnabled) return;
        try {
            JsonNode response = objectMapper.readTree(ragPipelineClient.reviewConsistency(
                    llmPrompt(files, specifications), llmModel));
            JsonNode llmFindings = response.path("findings");
            if (!llmFindings.isArray()) {
                findings.add(new ConsistencyFinding("INFO", "LLM", "LLM 응답에 구조화된 findings가 없어 규칙 기반 결과만 사용했습니다."));
                return;
            }
            for (JsonNode finding : llmFindings) {
                String severity = finding.path("severity").asText("INFO").toUpperCase();
                if (!Set.of("PASS", "INFO", "WARNING").contains(severity)) severity = "INFO";
                String area = finding.path("area").asText("LLM");
                String message = finding.path("message").asText("").trim();
                if (!message.isBlank()) findings.add(new ConsistencyFinding(severity, area, message));
            }
        } catch (Exception e) {
            findings.add(new ConsistencyFinding("INFO", "LLM", "LLM 검토를 완료하지 못해 규칙 기반 결과만 사용했습니다."));
        }
    }

    private String llmPrompt(List<GithubPullRequestFileInfo> files,
                             Map<PipelineArtifact.ArtifactType, String> specifications) {
        StringBuilder changedFiles = new StringBuilder();
        for (GithubPullRequestFileInfo file : files) {
            changedFiles.append("\n### ").append(file.filename()).append("\n")
                    .append(truncate(file.patch(), 4_000));
        }
        return """
                You review whether a pull request matches a product API specification and DB schema.
                Use only the supplied material. Do not invent endpoints, tables, or requirements.
                Return JSON only: {"findings":[{"severity":"PASS|INFO|WARNING","area":"API|DB|Cross-repo","message":"short Korean explanation"}]}.
                A WARNING is appropriate only when the diff gives concrete evidence of a mismatch or a required specification is missing.

                API_SPEC:
                %s

                DB_SCHEMA:
                %s

                PR changed files:
                %s
                """.formatted(
                truncate(specifications.get(PipelineArtifact.ArtifactType.API_SPEC), 12_000),
                truncate(specifications.get(PipelineArtifact.ArtifactType.DB_SCHEMA), 12_000),
                truncate(changedFiles.toString(), 20_000));
    }

    private String truncate(String text, int limit) {
        if (text == null || text.isBlank()) return "(없음)";
        return text.length() <= limit ? text : text.substring(0, limit) + "\n...[truncated]";
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

    private String reviewMarkdown(int score, List<ConsistencyFinding> findings) {
        StringBuilder body = new StringBuilder("## timiroom 정합성 자동 리뷰\n\n")
                .append("> 최신 `API_SPEC`·`DB_SCHEMA`와 PR 변경 파일을 규칙 기반으로 대조했습니다. 자동 승인이나 변경 요청은 하지 않습니다.\n\n")
                .append("**점수: ").append(score).append("/100**\n\n");
        for (ConsistencyFinding finding : findings) {
            String icon = switch (finding.severity()) {
                case "PASS" -> "✅";
                case "WARNING" -> "⚠️";
                default -> "ℹ️";
            };
            body.append("- ").append(icon).append(" **").append(finding.area()).append("** — ")
                    .append(finding.message()).append("\n");
        }
        body.append("\n---\n_timiroom GitHub App이 같은 PR head SHA에는 이 리뷰를 한 번만 게시합니다._");
        return body.toString();
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
                                                  List<RelatedPullRequestResponse> relatedPullRequests) {
        return new ProjectPullRequestResponse(repo.getId(), repo.getFullName(), pullRequest.number(), pullRequest.title(),
                pullRequest.body(), pullRequest.state(), pullRequest.draft(), pullRequest.headSha(), pullRequest.headRef(),
                pullRequest.baseRef(), pullRequest.htmlUrl(), pullRequest.authorLogin(), pullRequest.updatedAt(), relatedPullRequests);
    }

    private record PullWithRepo(GithubRepo repo, GithubPullRequestInfo pullRequest) {}
}

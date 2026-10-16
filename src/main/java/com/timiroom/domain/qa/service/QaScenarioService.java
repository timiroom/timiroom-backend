package com.timiroom.domain.qa.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.github.GithubPullRequestReviewRecord;
import com.timiroom.domain.github.GithubPullRequestReviewRecordRepository;
import com.timiroom.domain.github.GithubRepo;
import com.timiroom.domain.github.GithubRepoRepository;
import com.timiroom.domain.github.ProjectRepoLink;
import com.timiroom.domain.github.ProjectRepoLinkRepository;
import com.timiroom.domain.pipeline.entity.PipelineArtifact;
import com.timiroom.domain.pipeline.service.PipelineService;
import com.timiroom.domain.project.entity.Project;
import com.timiroom.domain.project.service.ProjectService;
import com.timiroom.domain.qa.dto.CreateQaScenarioRequest;
import com.timiroom.domain.qa.dto.GenerateQaScenarioRequest;
import com.timiroom.domain.qa.dto.QaScenarioResponse;
import com.timiroom.domain.qa.entity.QaScenario;
import com.timiroom.domain.qa.repository.QaScenarioRepository;
import com.timiroom.infra.github.GithubClient;
import com.timiroom.infra.ragpipeline.RagPipelineClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * QA 테스트 시나리오 — 생성·조회·실행.
 *
 * "실행"은 실제 코드를 컴파일·구동하지 않는다. 최신 API_SPEC·DB_SCHEMA와,
 * 연결된 레포에서 이 기능을 건드린 PR의 변경 파일(있다면)을 근거로 LLM이
 * 시나리오가 통과할지 추론한다. 근거가 부족하면 PASS/FAIL 대신 INCONCLUSIVE로 남는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaScenarioService {

    /** 근거로 첨부할 코드 파일 수 상한 — 그래프의 MAX_CONTEXT_FILES와 같은 이유로 상한을 둔다 */
    private static final int MAX_CODE_FILES = 6;
    private static final int MIN_TOKEN_LENGTH = 2;

    private final ProjectService projectService;
    private final PipelineService pipelineService;
    private final QaScenarioRepository qaScenarioRepository;
    private final ProjectRepoLinkRepository projectRepoLinkRepository;
    private final GithubRepoRepository githubRepoRepository;
    private final GithubPullRequestReviewRecordRepository reviewRecordRepository;
    private final GithubClient githubClient;
    private final RagPipelineClient ragPipelineClient;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<QaScenarioResponse> list(Long projectId, Long memberId) {
        projectService.getById(projectId, memberId);
        return qaScenarioRepository.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
                .map(scenario -> QaScenarioResponse.from(scenario, readEvidence(scenario.getEvidenceJson())))
                .toList();
    }

    @Transactional
    public QaScenarioResponse create(Long projectId, Long memberId, CreateQaScenarioRequest request) {
        projectService.getById(projectId, memberId);
        String title = request.title() == null ? "" : request.title().trim();
        String then = request.then() == null ? "" : request.then().trim();
        if (title.isBlank()) throw new IllegalArgumentException("시나리오 제목을 입력해 주세요.");
        if (then.isBlank()) throw new IllegalArgumentException("예상 결과(then)를 입력해 주세요.");

        QaScenario scenario = QaScenario.builder()
                .projectId(projectId)
                .featureName(blankToNull(request.featureName()))
                .type(parseType(request.type()))
                .title(title)
                .given(request.given())
                .whenStep(request.when())
                .thenResult(then)
                .source(QaScenario.Source.USER)
                .createdByMemberId(memberId)
                .build();
        qaScenarioRepository.save(scenario);
        return QaScenarioResponse.from(scenario, List.of());
    }

    @Transactional
    public List<QaScenarioResponse> generate(Long projectId, Long memberId, GenerateQaScenarioRequest request) {
        Project project = projectService.getById(projectId, memberId);
        String featureName = request.featureName();
        if (featureName == null || featureName.isBlank()) {
            throw new IllegalArgumentException("시나리오를 생성할 기능을 선택해 주세요.");
        }

        Map<PipelineArtifact.ArtifactType, String> artifacts = latestSpecifications(projectId);
        FeatureSpec feature = findFeature(artifacts.get(PipelineArtifact.ArtifactType.FEATURE_LIST), featureName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "기능 명세에서 '%s'을(를) 찾지 못했습니다.".formatted(featureName)));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectName", project.getProjectName());
        payload.put("featureName", feature.name());
        payload.put("featureDescription", feature.description());
        payload.put("requirements", String.join("\n", feature.requirements()));
        payload.put("apiSpec", artifacts.get(PipelineArtifact.ArtifactType.API_SPEC));
        payload.put("dbSchema", artifacts.get(PipelineArtifact.ArtifactType.DB_SCHEMA));

        JsonNode response = ragPipelineClient.generateQaScenarios(payload);
        List<QaScenario> saved = new ArrayList<>();
        for (JsonNode node : response.path("scenarios")) {
            QaScenario scenario = QaScenario.builder()
                    .projectId(projectId)
                    .featureName(feature.name())
                    .type(parseType(node.path("type").asText("NORMAL")))
                    .title(node.path("title").asText(""))
                    .given(node.path("given").asText(""))
                    .whenStep(node.path("when").asText(""))
                    .thenResult(node.path("then").asText(""))
                    .source(QaScenario.Source.AI)
                    .build();
            saved.add(qaScenarioRepository.save(scenario));
        }
        return saved.stream().map(scenario -> QaScenarioResponse.from(scenario, List.of())).toList();
    }

    @Transactional
    public QaScenarioResponse run(Long projectId, Long memberId, Long scenarioId) {
        Project project = projectService.getById(projectId, memberId);
        QaScenario scenario = getOwnedScenario(projectId, scenarioId);

        Map<PipelineArtifact.ArtifactType, String> artifacts = latestSpecifications(projectId);
        List<Map<String, String>> codeFiles = findRelatedCode(projectId, scenario);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectName", project.getProjectName());
        payload.put("featureName", scenario.getFeatureName());
        payload.put("scenarioTitle", scenario.getTitle());
        payload.put("given", scenario.getGiven());
        payload.put("when", scenario.getWhenStep());
        payload.put("then", scenario.getThenResult());
        payload.put("apiSpec", artifacts.get(PipelineArtifact.ArtifactType.API_SPEC));
        payload.put("dbSchema", artifacts.get(PipelineArtifact.ArtifactType.DB_SCHEMA));
        payload.put("codeFiles", codeFiles);

        JsonNode response = ragPipelineClient.evaluateQaScenario(payload);
        QaScenario.Verdict verdict = parseVerdict(response.path("verdict").asText("INCONCLUSIVE"));
        String reasoning = response.path("reasoning").asText("");
        List<String> evidence = new ArrayList<>();
        for (JsonNode node : response.path("evidence")) {
            String text = node.asText("");
            if (!text.isBlank()) evidence.add(text);
        }

        scenario.recordResult(verdict, reasoning, writeJson(evidence), LocalDateTime.now());
        return QaScenarioResponse.from(scenario, evidence);
    }

    @Transactional
    public void delete(Long projectId, Long memberId, Long scenarioId) {
        projectService.getById(projectId, memberId);
        qaScenarioRepository.delete(getOwnedScenario(projectId, scenarioId));
    }

    private QaScenario getOwnedScenario(Long projectId, Long scenarioId) {
        QaScenario scenario = qaScenarioRepository.findById(scenarioId)
                .orElseThrow(() -> new IllegalArgumentException("시나리오를 찾을 수 없습니다."));
        if (!scenario.getProjectId().equals(projectId)) {
            throw new SecurityException("다른 프로젝트의 시나리오입니다.");
        }
        return scenario;
    }

    private Map<PipelineArtifact.ArtifactType, String> latestSpecifications(Long projectId) {
        Map<PipelineArtifact.ArtifactType, String> result = new LinkedHashMap<>();
        pipelineService.getLatestArtifactsByProject(projectId)
                .forEach(artifact -> result.put(artifact.getArtifactType(), artifact.getContent()));
        return result;
    }

    private record FeatureSpec(String name, String description, List<String> requirements) {}

    private Optional<FeatureSpec> findFeature(String featureListJson, String featureName) {
        if (featureListJson == null) return Optional.empty();
        try {
            JsonNode root = objectMapper.readTree(featureListJson);
            JsonNode array = root.isArray() ? root : root.path("featureList");
            if (!array.isArray()) return Optional.empty();
            for (JsonNode item : array) {
                String name = item.isTextual() ? item.asText() : item.path("name").asText("");
                if (name.equalsIgnoreCase(featureName)) {
                    List<String> requirements = new ArrayList<>();
                    for (JsonNode requirement : item.path("requirements")) {
                        requirements.add(requirement.asText(""));
                    }
                    return Optional.of(new FeatureSpec(name, item.path("description").asText(""), requirements));
                }
            }
        } catch (Exception e) {
            log.warn("FEATURE_LIST 파싱 실패 — projectId 무관, 근거 없이 진행: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * 시나리오의 기능·제목과 겹치는 API를 건드린 PR의 변경 파일을 모은다 (best-effort).
     * 관련 코드를 찾지 못해도 실패하지 않는다 — evaluate 프롬프트가 그 경우 명세만으로
     * INCONCLUSIVE 쪽으로 기울도록 지시돼 있다.
     */
    private List<Map<String, String>> findRelatedCode(Long projectId, QaScenario scenario) {
        List<ProjectRepoLink> links = projectRepoLinkRepository.findByProjectId(projectId);
        if (links.isEmpty()) return List.of();

        List<Long> repoIds = links.stream().map(ProjectRepoLink::getGithubRepoId).toList();
        Map<Long, GithubRepo> reposById = githubRepoRepository.findByIdIn(repoIds).stream()
                .collect(Collectors.toMap(GithubRepo::getId, repo -> repo));

        Set<String> keywords = tokenize(
                (scenario.getFeatureName() == null ? "" : scenario.getFeatureName()) + " " + scenario.getTitle());
        if (keywords.isEmpty()) return List.of();

        List<Map<String, String>> files = new ArrayList<>();
        Set<String> seenPaths = new LinkedHashSet<>();

        for (GithubPullRequestReviewRecord record : reviewRecordRepository.findByProjectIdAndGithubRepoIdIn(projectId, repoIds)) {
            if (files.size() >= MAX_CODE_FILES || record.getTouchedJson() == null) continue;
            GithubRepo repo = reposById.get(record.getGithubRepoId());
            if (repo == null) continue;

            try {
                JsonNode touched = objectMapper.readTree(record.getTouchedJson());
                String apisText = String.join(" ", asTextList(touched.path("apis"))).toLowerCase(Locale.ROOT);
                boolean matches = keywords.stream().anyMatch(apisText::contains);
                if (!matches) continue;

                for (String path : asTextList(touched.path("files"))) {
                    if (files.size() >= MAX_CODE_FILES || !seenPaths.add(path)) continue;
                    githubClient.getRepositoryFileContent(repo.getFullName(), repo.getInstallationId(), path, repo.getDefaultBranch())
                            .ifPresent(content -> files.add(Map.of("filename", path, "content", content)));
                }
            } catch (Exception e) {
                log.debug("touched_json 파싱 실패 — 이 PR은 건너뜀: {}", e.getMessage());
            }
        }
        return files;
    }

    private List<String> asTextList(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode node : array) values.add(node.asText(""));
        }
        return values;
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null) return tokens;
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9가-힣]+")) {
            if (token.length() >= MIN_TOKEN_LENGTH) tokens.add(token);
        }
        return tokens;
    }

    private List<String> readEvidence(String evidenceJson) {
        if (evidenceJson == null || evidenceJson.isBlank()) return List.of();
        try {
            List<String> result = new ArrayList<>();
            for (JsonNode node : objectMapper.readTree(evidenceJson)) result.add(node.asText(""));
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private QaScenario.Type parseType(String value) {
        try {
            return QaScenario.Type.valueOf(value == null ? "" : value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return QaScenario.Type.NORMAL;
        }
    }

    private QaScenario.Verdict parseVerdict(String value) {
        try {
            return QaScenario.Verdict.valueOf(value == null ? "" : value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return QaScenario.Verdict.INCONCLUSIVE;
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

package com.timiroom.domain.graph.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.github.GithubPullRequestReviewRecord;
import com.timiroom.domain.github.GithubPullRequestReviewRecordRepository;
import com.timiroom.domain.github.ProjectRepoLink;
import com.timiroom.domain.github.ProjectRepoLinkRepository;
import com.timiroom.domain.github.dto.PullRequestTouchPoints;
import com.timiroom.domain.graph.dto.GraphResponse;
import com.timiroom.domain.pipeline.entity.PipelineArtifact;
import com.timiroom.domain.pipeline.repository.ArtifactRevisionRepository;
import com.timiroom.domain.pipeline.service.PipelineService;
import com.timiroom.domain.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.timiroom.domain.graph.dto.PrTouchPoint;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeGraphService {
    private final ProjectService projectService;
    private final PipelineService pipelineService;
    private final ArtifactRevisionRepository revisionRepository;
    private final GithubPullRequestReviewRecordRepository reviewRecordRepository;
    private final ProjectRepoLinkRepository projectRepoLinkRepository;
    private final ObjectMapper objectMapper;
    @Transactional(readOnly=true)
    public GraphResponse build(Long projectId,Long memberId) {
        projectService.getById(projectId,memberId);
        return build(projectId);
    }
    @Transactional(readOnly=true)
    public GraphResponse build(Long projectId) {
        return new GraphCalculator(objectMapper).calculateMaps(loadArtifacts(projectId),loadPreviousArtifacts(projectId),readPullRequests(projectId));
    }
    private Map<PipelineArtifact.ArtifactType, JsonNode> loadPreviousArtifacts(Long projectId) {
        Map<PipelineArtifact.ArtifactType, JsonNode> result = new LinkedHashMap<>();
        for (PipelineArtifact artifact : pipelineService.getLatestArtifactsByProject(projectId)) {
            revisionRepository.findFirstByArtifactIdOrderByVersionDesc(artifact.getArtifactId())
                    .ifPresent(revision -> {
                        try {
                            result.putIfAbsent(artifact.getArtifactType(),
                                    objectMapper.readTree(revision.getContent()));
                        } catch (Exception e) {
                            log.warn("이전 버전 파싱 실패 | artifactId: {}", artifact.getArtifactId());
                        }
                    });
        }
        return result;
    }

    /**
     * 이 프로젝트에 연결된 레포에서 정합성 검사를 마친 PR을 읽는다.
     *
     * 검사 기록이 없는 PR은 그래프에 올리지 않는다. 접점을 뽑아 둔 시점이 곧 검사 시점이라
     * 기록이 없으면 무엇을 건드렸는지 알 수 없고, 이름만 띄운 노드는 선이 없어 오히려
     * "아무 데도 영향이 없다"는 잘못된 인상을 준다.
     *
     * 닫히거나 머지된 PR도 내린다 — 여기 있는 PR은 "지금 진행 중"이라는 뜻이어야 한다.
     */
    private List<PrTouchPoint> readPullRequests(Long projectId) {
        List<Long> repoIds = projectRepoLinkRepository.findByProjectId(projectId).stream()
                .map(ProjectRepoLink::getGithubRepoId)
                .toList();
        if (repoIds.isEmpty()) return List.of();

        List<PrTouchPoint> result = new ArrayList<>();
        for (GithubPullRequestReviewRecord record : reviewRecordRepository
                .findByProjectIdAndGithubRepoIdIn(projectId, repoIds)) {
            if (!record.isOpenForGraph()) continue;

            PullRequestTouchPoints touched;
            try {
                touched = objectMapper.readValue(record.getTouchedJson(), PullRequestTouchPoints.class);
            } catch (Exception e) {
                log.warn("PR 접점 파싱 실패 | pr: #{}", record.getPullNumber());
                continue;
            }
            if (touched.apis().isEmpty() && touched.tables().isEmpty()) continue;

            String title = record.getPullTitle() == null ? "" : record.getPullTitle();
            result.add(new PrTouchPoint(
                    "pr:" + record.getGithubRepoId() + ":" + record.getPullNumber(),
                    "#" + record.getPullNumber() + (title.isBlank() ? "" : " " + title),
                    record.getPullNumber(),
                    record.getPullUrl() == null ? "" : record.getPullUrl(),
                    record.getReviewUrl() == null ? "" : record.getReviewUrl(),
                    String.valueOf(record.getGithubRepoId()),
                    record.getScore() == null ? 0 : record.getScore(),
                    record.getEvaluator() == null ? "" : record.getEvaluator(),
                    countWarnings(record.getFindingsJson()),
                    touched, record.getHeadSha()));
        }
        return result;
    }

    /** 경고 건수만 센다 — 그래프에는 몇 건인지가 필요하고, 본문은 명세 패널이 이미 보여준다 */
    private int countWarnings(String findingsJson) {
        if (findingsJson == null) return 0;
        try {
            int warnings = 0;
            for (JsonNode finding : objectMapper.readTree(findingsJson)) {
                if ("WARNING".equalsIgnoreCase(finding.path("severity").asText())) warnings++;
            }
            return warnings;
        } catch (Exception e) {
            return 0;
        }
    }

    private Map<PipelineArtifact.ArtifactType, JsonNode> loadArtifacts(Long projectId) {
        Map<PipelineArtifact.ArtifactType, JsonNode> result = new LinkedHashMap<>();
        for (PipelineArtifact artifact : pipelineService.getLatestArtifactsByProject(projectId)) {
            try {
                result.putIfAbsent(artifact.getArtifactType(), objectMapper.readTree(artifact.getContent()));
            } catch (Exception e) {
                log.warn("아티팩트 파싱 실패 | type: {}, projectId: {}", artifact.getArtifactType(), projectId);
            }
        }
        return result;
    }

}

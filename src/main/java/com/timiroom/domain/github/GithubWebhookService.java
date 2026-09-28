package com.timiroom.domain.github;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * pull_request 이벤트를 받아 연결 프로젝트의 정합성 review를 자동 실행하고,
 * PR이 닫히면 지식 그래프에서 내린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GithubWebhookService {

    private static final Set<String> REVIEW_ACTIONS = Set.of("opened", "reopened", "synchronize", "ready_for_review");

    private final GithubRepoRepository githubRepoRepository;
    private final ProjectRepoLinkRepository projectRepoLinkRepository;
    private final GithubPullRequestReviewRecordRepository reviewRecordRepository;
    private final PullRequestConsistencyService pullRequestConsistencyService;

    @Async
    public void handle(String event, JsonNode payload) {
        if (!"pull_request".equals(event)) return;
        String action = payload.path("action").asText();
        boolean review = REVIEW_ACTIONS.contains(action);
        boolean closed = "closed".equals(action);
        if (!review && !closed) return;

        long githubRepoId = payload.path("repository").path("id").asLong(-1);
        int pullNumber = payload.path("number").asInt(-1);
        if (githubRepoId < 0 || pullNumber < 1) {
            log.warn("GitHub pull_request webhook 필수 값 누락 — repoId={}, pr={}", githubRepoId, pullNumber);
            return;
        }
        githubRepoRepository.findByGithubRepoId(githubRepoId).ifPresentOrElse(repo ->
                        projectRepoLinkRepository.findByGithubRepoId(repo.getId()).forEach(link -> {
                            try {
                                if (closed) closeOnGraph(link.getProjectId(), repo, pullNumber, payload);
                                else runReview(link.getProjectId(), repo, pullNumber);
                            } catch (Exception e) {
                                // GitHub은 2xx 응답을 받아야 재시도 폭주를 피할 수 있으므로 실패는 로그로 남긴다.
                                log.error("GitHub pull_request webhook 처리 실패 — project={}, repo={}, pr={}, action={}: {}",
                                        link.getProjectId(), repo.getFullName(), pullNumber, action, e.getMessage(), e);
                            }
                        }),
                () -> log.debug("연결되지 않은 GitHub repo webhook 무시 — githubRepoId={}", githubRepoId));
    }

    private void runReview(Long projectId, GithubRepo repo, int pullNumber) {
        var result = pullRequestConsistencyService.checkAndReviewFromWebhook(projectId, repo.getId(), pullNumber);
        log.info("GitHub PR 정합성 자동 리뷰 — project={}, repo={}, pr={}, posted={}, duplicate={}",
                projectId, repo.getFullName(), pullNumber, result.reviewPosted(), result.skippedDuplicate());
    }

    /**
     * 머지되거나 그냥 닫힌 PR을 그래프에서 내린다.
     *
     * 검사 기록 자체는 지우지 않는다. 명세 패널이 과거 리뷰 결과를 읽고 있고,
     * 같은 PR이 다시 열리면(reopened) 그때 상태만 되돌리면 되기 때문이다.
     */
    private void closeOnGraph(Long projectId, GithubRepo repo, int pullNumber, JsonNode payload) {
        reviewRecordRepository.findByProjectIdAndGithubRepoIdAndPullNumber(projectId, repo.getId(), pullNumber)
                .ifPresent(record -> {
                    record.markClosed();
                    reviewRecordRepository.save(record);
                    log.info("PR 종료로 지식 그래프에서 제외 — project={}, repo={}, pr={}, merged={}",
                            projectId, repo.getFullName(), pullNumber,
                            payload.path("pull_request").path("merged").asBoolean(false));
                });
    }
}

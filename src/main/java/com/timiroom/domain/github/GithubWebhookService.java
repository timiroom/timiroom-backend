package com.timiroom.domain.github;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Set;

/** pull_request opened/synchronize 이벤트에서 연결 프로젝트의 정합성 review를 자동 실행한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class GithubWebhookService {

    private static final Set<String> REVIEW_ACTIONS = Set.of("opened", "reopened", "synchronize", "ready_for_review");

    private final GithubRepoRepository githubRepoRepository;
    private final ProjectRepoLinkRepository projectRepoLinkRepository;
    private final PullRequestConsistencyService pullRequestConsistencyService;

    @Async
    public void handle(String event, JsonNode payload) {
        if (!"pull_request".equals(event)) return;
        String action = payload.path("action").asText();
        if (!REVIEW_ACTIONS.contains(action)) return;

        long githubRepoId = payload.path("repository").path("id").asLong(-1);
        int pullNumber = payload.path("number").asInt(-1);
        if (githubRepoId < 0 || pullNumber < 1) {
            log.warn("GitHub pull_request webhook 필수 값 누락 — repoId={}, pr={}", githubRepoId, pullNumber);
            return;
        }
        githubRepoRepository.findByGithubRepoId(githubRepoId).ifPresentOrElse(repo ->
                        projectRepoLinkRepository.findByGithubRepoId(repo.getId()).forEach(link -> {
                            try {
                                var result = pullRequestConsistencyService.checkAndReviewFromWebhook(
                                        link.getProjectId(), repo.getId(), pullNumber);
                                log.info("GitHub PR 정합성 자동 리뷰 — project={}, repo={}, pr={}, posted={}, duplicate={}",
                                        link.getProjectId(), repo.getFullName(), pullNumber,
                                        result.reviewPosted(), result.skippedDuplicate());
                            } catch (Exception e) {
                                // GitHub은 2xx 응답을 받아야 재시도 폭주를 피할 수 있으므로 실패는 로그로 남긴다.
                                log.error("GitHub PR 정합성 자동 리뷰 실패 — project={}, repo={}, pr={}: {}",
                                        link.getProjectId(), repo.getFullName(), pullNumber, e.getMessage(), e);
                            }
                        }),
                () -> log.debug("연결되지 않은 GitHub repo webhook 무시 — githubRepoId={}", githubRepoId));
    }
}

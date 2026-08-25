package com.timiroom.domain.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * push 웹훅 처리 검증.
 *
 * 이 기능의 값어치는 "검사를 돌린다"보다 "돌리지 말아야 할 때 돌리지 않는다"에 있다.
 * GitHub은 브랜치 생성·삭제·태그까지 전부 push로 보내오고, 배포 파이프라인이
 * 만들어 내는 커밋도 섞여 든다. 아무거나 다 검사하면 GitHub 호출 한도를 먹고
 * 알림만 시끄러워진다. 그래서 걸러 내는 쪽을 중심으로 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GithubWebhookServiceTest {

    @Mock GithubRepoRepository githubRepoRepository;
    @Mock ProjectRepoLinkRepository projectRepoLinkRepository;
    @Mock GithubPullRequestReviewRecordRepository reviewRecordRepository;
    @Mock PullRequestConsistencyService pullRequestConsistencyService;

    @InjectMocks GithubWebhookService service;

    private final ObjectMapper mapper = new ObjectMapper();

    private static final long GITHUB_REPO_ID = 987654L;
    private static final String BEFORE = "1111111111111111111111111111111111111111";
    private static final String AFTER  = "2222222222222222222222222222222222222222";
    private static final String EMPTY  = "0000000000000000000000000000000000000000";

    @BeforeEach
    void setUp() {
        GithubRepo repo = GithubRepo.builder()
                .githubRepoId(GITHUB_REPO_ID)
                .fullName("timiroom/timiroom-backend")
                .installationId(146037712L)
                .build();
        given(githubRepoRepository.findByGithubRepoId(GITHUB_REPO_ID)).willReturn(Optional.of(repo));
        given(projectRepoLinkRepository.findByGithubRepoId(any())).willReturn(List.of(
                ProjectRepoLink.builder().projectId(1L).githubRepoId(repo.getId()).build()));
    }

    private JsonNode push(String ref, String before, String after, String message) {
        try {
            return mapper.readTree("""
                    {
                      "ref": "%s",
                      "before": "%s",
                      "after": "%s",
                      "repository": { "id": %d },
                      "head_commit": { "message": "%s" }
                    }
                    """.formatted(ref, before, after, GITHUB_REPO_ID, message));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("브랜치에 바로 올라온 커밋은 명세와 대조한다")
    void 직접_push를_검사한다() {
        service.handle("push", push("refs/heads/develop", BEFORE, AFTER, "fix: 응답 필드 추가"));

        then(pullRequestConsistencyService).should(times(1))
                .checkPush(eq(1L), any(), eq(BEFORE), eq(AFTER), eq("fix: 응답 필드 추가"));
    }

    @Test
    @DisplayName("PR 머지 커밋은 다시 검사하지 않는다")
    void 머지_커밋은_건너뛴다() {
        // PR 경로에서 이미 검사했다. 같은 변경을 두 번 검사하면 알림도 두 번 간다.
        service.handle("push", push("refs/heads/develop", BEFORE, AFTER,
                "Merge pull request #41 from timiroom/feat/knowledge-graph"));

        then(pullRequestConsistencyService).should(never())
                .checkPush(anyLong(), any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("브랜치를 처음 만들거나 지울 때는 비교할 앞이 없어 건너뛴다")
    void 빈_SHA는_건너뛴다() {
        service.handle("push", push("refs/heads/feature/new", EMPTY, AFTER, "첫 커밋"));
        service.handle("push", push("refs/heads/feature/old", BEFORE, EMPTY, "브랜치 삭제"));

        then(pullRequestConsistencyService).should(never())
                .checkPush(anyLong(), any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("태그 push는 코드 변경이 아니므로 건너뛴다")
    void 태그는_건너뛴다() {
        service.handle("push", push("refs/tags/v1.2.0", BEFORE, AFTER, "release"));

        then(pullRequestConsistencyService).should(never())
                .checkPush(anyLong(), any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("연결되지 않은 레포의 push는 무시한다")
    void 연결되지_않은_레포는_무시한다() {
        given(githubRepoRepository.findByGithubRepoId(GITHUB_REPO_ID)).willReturn(Optional.empty());

        service.handle("push", push("refs/heads/develop", BEFORE, AFTER, "fix: 무언가"));

        then(pullRequestConsistencyService).should(never())
                .checkPush(anyLong(), any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("검사 중 예외가 나도 웹훅 처리는 멈추지 않는다")
    void 실패해도_예외를_밖으로_던지지_않는다() {
        // GitHub은 2xx를 받아야 재시도 폭주를 피한다. 실패는 로그로만 남겨야 한다.
        org.mockito.BDDMockito.willThrow(new IllegalStateException("GitHub 호출 실패"))
                .given(pullRequestConsistencyService)
                .checkPush(anyLong(), any(), anyString(), anyString(), anyString());

        service.handle("push", push("refs/heads/develop", BEFORE, AFTER, "fix: 무언가"));
    }

    @Test
    @DisplayName("push 외의 모르는 이벤트는 그냥 흘려보낸다")
    void 모르는_이벤트는_무시한다() {
        service.handle("issues", push("refs/heads/develop", BEFORE, AFTER, "무관"));

        then(pullRequestConsistencyService).should(never())
                .checkPush(anyLong(), any(), anyString(), anyString(), anyString());
    }
}

package com.timiroom.domain.github;

import com.timiroom.domain.pipeline.PipelineArtifact;
import com.timiroom.domain.pipeline.PipelineService;
import com.timiroom.domain.notification.NotificationService;
import com.timiroom.domain.notification.NotificationReferenceType;
import com.timiroom.domain.notification.NotificationType;
import com.timiroom.domain.project.ProjectMember;
import com.timiroom.domain.project.ProjectMemberRepository;
import com.timiroom.domain.project.ProjectRole;
import com.timiroom.domain.project.ProjectService;
import com.timiroom.infra.github.GithubClient;
import com.timiroom.infra.github.dto.GithubPullRequestFileInfo;
import com.timiroom.infra.github.dto.GithubPullRequestInfo;
import com.timiroom.infra.github.dto.GithubPullRequestReviewInfo;
import com.timiroom.infra.github.dto.GithubCheckRunInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PullRequestConsistencyServiceTest {

    private static final long PROJECT_ID = 1L;
    private static final long MEMBER_ID = 10L;
    private static final long REPO_ID = 99L;
    private static final long INSTALLATION_ID = 146037712L;

    @Mock ProjectService projectService;
    @Mock ProjectMemberRepository projectMemberRepository;
    @Mock ProjectRepoLinkRepository projectRepoLinkRepository;
    @Mock GithubRepoRepository githubRepoRepository;
    @Mock GithubPullRequestReviewRecordRepository reviewRecordRepository;
    @Mock PipelineService pipelineService;
    @Mock NotificationService notificationService;
    @Mock GithubClient githubClient;
    @Spy ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks PullRequestConsistencyService service;

    private void givenLinkedPm() {
        when(projectMemberRepository.findByProjectIdAndMemberId(PROJECT_ID, MEMBER_ID))
                .thenReturn(Optional.of(ProjectMember.builder().projectId(PROJECT_ID).memberId(MEMBER_ID)
                        .projectRole(ProjectRole.PM).build()));
        when(projectRepoLinkRepository.findByProjectIdAndGithubRepoId(PROJECT_ID, REPO_ID))
                .thenReturn(Optional.of(ProjectRepoLink.builder().projectId(PROJECT_ID).githubRepoId(REPO_ID).build()));
        when(githubRepoRepository.findById(REPO_ID)).thenReturn(Optional.of(GithubRepo.builder()
                .id(REPO_ID).githubRepoId(555L).fullName("timiroom/timiroom-backend")
                .installationId(INSTALLATION_ID).build()));
        when(githubClient.getPullRequest("timiroom/timiroom-backend", INSTALLATION_ID, 42))
                .thenReturn(new GithubPullRequestInfo(42, "feat: task API", "", "open", false, "head-sha",
                        "feature/task", "develop", "https://github.com/timiroom/timiroom-backend/pull/42", "dev", "2026-07-12T10:00:00Z"));
        when(githubClient.listPullRequestFiles("timiroom/timiroom-backend", INSTALLATION_ID, 42))
                .thenReturn(List.of(new GithubPullRequestFileInfo("TaskController.java", "modified", 3, 0,
                        "+ @GetMapping(\"/api/v1/tasks\")")));
    }

    @Test
    void checkAndReview_명세와_대조한_summary_comment를_한번_게시한다() {
        givenLinkedPm();
        when(pipelineService.getLatestArtifactsByProject(PROJECT_ID)).thenReturn(List.of(
                PipelineArtifact.builder().artifactType(PipelineArtifact.ArtifactType.API_SPEC)
                        .content("GET /api/v1/tasks").build()));
        when(reviewRecordRepository.findByProjectIdAndGithubRepoIdAndPullNumber(PROJECT_ID, REPO_ID, 42))
                .thenReturn(Optional.empty());
        when(githubClient.createCompletedConsistencyCheckRun(eq("timiroom/timiroom-backend"), eq(INSTALLATION_ID),
                eq("head-sha"), eq(100), eq(false), any())).thenReturn(new GithubCheckRunInfo(9L, "https://check", "success"));
        when(githubClient.createPullRequestCommentReview(eq("timiroom/timiroom-backend"), eq(INSTALLATION_ID),
                eq(42), eq("head-sha"), any())).thenReturn(new GithubPullRequestReviewInfo(7L, "https://review", "COMMENTED"));

        var result = service.checkAndReview(PROJECT_ID, MEMBER_ID, REPO_ID, 42);

        assertThat(result.score()).isEqualTo(100);
        assertThat(result.reviewPosted()).isTrue();
        assertThat(result.checkRunUrl()).isEqualTo("https://check");
        assertThat(result.findings()).anyMatch(f -> "PASS".equals(f.severity()) && f.message().contains("/api/v1/tasks"));
        ArgumentCaptor<String> reviewBody = ArgumentCaptor.forClass(String.class);
        verify(githubClient).createPullRequestCommentReview(eq("timiroom/timiroom-backend"), eq(INSTALLATION_ID),
                eq(42), eq("head-sha"), reviewBody.capture());
        assertThat(reviewBody.getValue()).contains("정합성 자동 리뷰").contains("100/100");
        verify(reviewRecordRepository).save(any(GithubPullRequestReviewRecord.class));
    }

    @Test
    void checkAndReview_같은_head_sha에는_중복_review를_남기지_않는다() {
        givenLinkedPm();
        when(pipelineService.getLatestArtifactsByProject(PROJECT_ID)).thenReturn(List.of());
        when(reviewRecordRepository.findByProjectIdAndGithubRepoIdAndPullNumber(PROJECT_ID, REPO_ID, 42))
                .thenReturn(Optional.of(GithubPullRequestReviewRecord.builder().projectId(PROJECT_ID)
                        .githubRepoId(REPO_ID).pullNumber(42).headSha("head-sha").reviewUrl("https://review").build()));

        var result = service.checkAndReview(PROJECT_ID, MEMBER_ID, REPO_ID, 42);

        assertThat(result.skippedDuplicate()).isTrue();
        assertThat(result.reviewPosted()).isFalse();
        verify(githubClient, never()).createPullRequestCommentReview(any(), anyLong(), anyInt(), any(), any());
    }

    @Test
    void checkAndReview_경고가_있으면_프로젝트_멤버에게_알림을_만든다() {
        givenLinkedPm();
        when(pipelineService.getLatestArtifactsByProject(PROJECT_ID)).thenReturn(List.of());
        when(reviewRecordRepository.findByProjectIdAndGithubRepoIdAndPullNumber(PROJECT_ID, REPO_ID, 42))
                .thenReturn(Optional.empty());
        when(githubClient.createCompletedConsistencyCheckRun(eq("timiroom/timiroom-backend"), eq(INSTALLATION_ID),
                eq("head-sha"), eq(75), eq(true), any())).thenReturn(new GithubCheckRunInfo(9L, "https://check", "neutral"));
        when(githubClient.createPullRequestCommentReview(eq("timiroom/timiroom-backend"), eq(INSTALLATION_ID),
                eq(42), eq("head-sha"), any())).thenReturn(new GithubPullRequestReviewInfo(7L, "https://review", "COMMENTED"));
        when(projectMemberRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(
                ProjectMember.builder().projectId(PROJECT_ID).memberId(MEMBER_ID).projectRole(ProjectRole.PM).build()));

        var result = service.checkAndReview(PROJECT_ID, MEMBER_ID, REPO_ID, 42);

        assertThat(result.score()).isEqualTo(75);
        verify(notificationService).create(eq(MEMBER_ID), eq(NotificationType.PR_CONSISTENCY_REVIEW),
                any(), any(), eq(NotificationReferenceType.PULL_REQUEST), eq(42L));
    }

    @Test
    void getLatestSummary_검사_이력이_없으면_null을_반환한다() {
        when(projectRepoLinkRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(
                ProjectRepoLink.builder().projectId(PROJECT_ID).githubRepoId(REPO_ID).build()));
        when(reviewRecordRepository.findFirstByProjectIdAndGithubRepoIdInOrderByUpdatedAtDesc(
                PROJECT_ID, List.of(REPO_ID))).thenReturn(Optional.empty());

        var summary = service.getLatestSummary(PROJECT_ID, MEMBER_ID);

        assertThat(summary).isNull();
    }

    @Test
    void getLatestSummary_가장_최근_검사_결과를_findings와_함께_반환한다() {
        givenLinkedPm();
        when(pipelineService.getLatestArtifactsByProject(PROJECT_ID)).thenReturn(List.of(
                PipelineArtifact.builder().artifactType(PipelineArtifact.ArtifactType.API_SPEC)
                        .content("GET /api/v1/tasks").build()));
        when(reviewRecordRepository.findByProjectIdAndGithubRepoIdAndPullNumber(PROJECT_ID, REPO_ID, 42))
                .thenReturn(Optional.empty());
        when(githubClient.createCompletedConsistencyCheckRun(eq("timiroom/timiroom-backend"), eq(INSTALLATION_ID),
                eq("head-sha"), eq(100), eq(false), any())).thenReturn(new GithubCheckRunInfo(9L, "https://check", "success"));
        when(githubClient.createPullRequestCommentReview(eq("timiroom/timiroom-backend"), eq(INSTALLATION_ID),
                eq(42), eq("head-sha"), any())).thenReturn(new GithubPullRequestReviewInfo(7L, "https://review", "COMMENTED"));
        ArgumentCaptor<GithubPullRequestReviewRecord> savedRecord = ArgumentCaptor.forClass(GithubPullRequestReviewRecord.class);

        service.checkAndReview(PROJECT_ID, MEMBER_ID, REPO_ID, 42);
        verify(reviewRecordRepository).save(savedRecord.capture());
        when(projectRepoLinkRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(
                ProjectRepoLink.builder().projectId(PROJECT_ID).githubRepoId(REPO_ID).build()));
        when(reviewRecordRepository.findFirstByProjectIdAndGithubRepoIdInOrderByUpdatedAtDesc(
                PROJECT_ID, List.of(REPO_ID)))
                .thenReturn(Optional.of(savedRecord.getValue()));

        var summary = service.getLatestSummary(PROJECT_ID, MEMBER_ID);

        assertThat(summary).isNotNull();
        assertThat(summary.repoFullName()).isEqualTo("timiroom/timiroom-backend");
        assertThat(summary.pullNumber()).isEqualTo(42);
        assertThat(summary.score()).isEqualTo(100);
        assertThat(summary.findings()).anyMatch(f -> "PASS".equals(f.severity()) && f.message().contains("/api/v1/tasks"));
    }

    @Test
    void getLatestSummary_연결된_레포가_없으면_과거_검사결과를_노출하지_않는다() {
        when(projectRepoLinkRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of());

        var summary = service.getLatestSummary(PROJECT_ID, MEMBER_ID);

        assertThat(summary).isNull();
        verify(reviewRecordRepository, never())
                .findFirstByProjectIdAndGithubRepoIdInOrderByUpdatedAtDesc(anyLong(), any());
    }

    @Test
    void list_같은_이슈_참조를_가진_다른_레포_PR을_그룹으로_반환한다() {
        GithubRepo frontendRepo = GithubRepo.builder().id(98L).githubRepoId(556L)
                .fullName("timiroom/timiroom-frontend").installationId(INSTALLATION_ID).build();
        when(projectRepoLinkRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(
                ProjectRepoLink.builder().projectId(PROJECT_ID).githubRepoId(REPO_ID).build(),
                ProjectRepoLink.builder().projectId(PROJECT_ID).githubRepoId(98L).build()));
        when(githubRepoRepository.findById(REPO_ID)).thenReturn(Optional.of(GithubRepo.builder().id(REPO_ID)
                .fullName("timiroom/timiroom-backend").installationId(INSTALLATION_ID).build()));
        when(githubRepoRepository.findById(98L)).thenReturn(Optional.of(frontendRepo));
        when(githubClient.listPullRequests("timiroom/timiroom-backend", INSTALLATION_ID)).thenReturn(List.of(
                new GithubPullRequestInfo(42, "feat: login #24", "", "open", false, "sha1", "feature/24-login",
                        "develop", "https://backend/pr/42", "dev", "2026-07-12T10:00:00Z")));
        when(githubClient.listPullRequests("timiroom/timiroom-frontend", INSTALLATION_ID)).thenReturn(List.of(
                new GithubPullRequestInfo(12, "feat: login UI #24", "", "open", false, "sha2", "feature/24-login-ui",
                        "develop", "https://frontend/pr/12", "dev", "2026-07-12T11:00:00Z")));

        var pulls = service.list(PROJECT_ID, MEMBER_ID);

        assertThat(pulls).hasSize(2);
        assertThat(pulls.stream().filter(pull -> pull.repoId().equals(REPO_ID)).findFirst().orElseThrow().relatedPullRequests())
                .extracting(related -> related.repoFullName()).containsExactly("timiroom/timiroom-frontend");
    }
}

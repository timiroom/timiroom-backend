package com.timiroom.domain.github;

import com.timiroom.domain.pipeline.entity.PipelineArtifact;
import com.timiroom.domain.pipeline.service.PipelineService;
import com.timiroom.domain.notification.service.NotificationService;
import com.timiroom.domain.notification.enums.NotificationReferenceType;
import com.timiroom.domain.notification.enums.NotificationType;
import com.timiroom.domain.project.entity.mapping.ProjectMember;
import com.timiroom.domain.project.repository.ProjectMemberRepository;
import com.timiroom.domain.project.enums.ProjectRole;
import com.timiroom.domain.project.service.ProjectService;
import com.timiroom.infra.github.GithubClient;
import com.timiroom.infra.github.dto.GithubPullRequestFileInfo;
import com.timiroom.infra.github.dto.GithubPullRequestInfo;
import com.timiroom.infra.github.dto.GithubPullRequestReviewInfo;
import com.timiroom.infra.github.dto.GithubCheckRunInfo;
import com.timiroom.infra.consistency.ConsistencyServiceClient;
import com.timiroom.infra.ragpipeline.RagPipelineClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
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
    @Mock RagPipelineClient ragPipelineClient;
    @Mock ConsistencyServiceClient consistencyServiceClient;
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
        lenient().when(githubClient.listPullRequestFiles("timiroom/timiroom-backend", INSTALLATION_ID, 42))
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
        assertThat(result.evaluator()).isEqualTo("RULES");
        assertThat(result.reviewPosted()).isTrue();
        assertThat(result.checkRunUrl()).isEqualTo("https://check");
        assertThat(result.findings()).anyMatch(f -> "PASS".equals(f.severity()) && f.message().contains("/api/v1/tasks"));
        ArgumentCaptor<String> reviewBody = ArgumentCaptor.forClass(String.class);
        verify(githubClient).createPullRequestCommentReview(eq("timiroom/timiroom-backend"), eq(INSTALLATION_ID),
                eq(42), eq("head-sha"), reviewBody.capture());
        assertThat(reviewBody.getValue())
                .contains("timiroom PR 정합성 리뷰")
                .contains("리뷰 요약")
                .contains("정합성 점수")
                .contains("100/100");
        verify(reviewRecordRepository).save(any(GithubPullRequestReviewRecord.class));
    }

    @Test
    void checkAndReview_같은_head_sha에는_중복_review를_남기지_않는다() {
        givenLinkedPm();
        when(reviewRecordRepository.findByProjectIdAndGithubRepoIdAndPullNumber(PROJECT_ID, REPO_ID, 42))
                .thenReturn(Optional.of(GithubPullRequestReviewRecord.builder().projectId(PROJECT_ID)
                        .githubRepoId(REPO_ID).pullNumber(42).headSha("head-sha").reviewUrl("https://review")
                        .evaluator("PYTHON_EXAONE").build()));

        var result = service.checkAndReview(PROJECT_ID, MEMBER_ID, REPO_ID, 42);

        assertThat(result.skippedDuplicate()).isTrue();
        assertThat(result.reviewPosted()).isFalse();
        assertThat(result.evaluator()).isEqualTo("PYTHON_EXAONE");
        verify(githubClient, never()).createPullRequestCommentReview(any(), anyLong(), anyInt(), any(), any());
    }

    @Test
    void checkAndReview_Spring_해외_Consistency_Agent_판정을_사용한다() throws Exception {
        givenLinkedPm();
        ReflectionTestUtils.setField(service, "agentEnabled", true);
        ReflectionTestUtils.setField(service, "agentModel", "gpt-5.4-mini");
        ReflectionTestUtils.setField(service, "agentRuntime", "SPRING");
        when(pipelineService.getLatestArtifactsByProject(PROJECT_ID)).thenReturn(List.of(
                PipelineArtifact.builder().artifactType(PipelineArtifact.ArtifactType.API_SPEC)
                        .content("GET /api/v1/tasks").build()));
        when(reviewRecordRepository.findByProjectIdAndGithubRepoIdAndPullNumber(PROJECT_ID, REPO_ID, 42))
                .thenReturn(Optional.empty());
        var agentResponse = new ObjectMapper().readTree("""
                {"agent":"PR_CONSISTENCY_AGENT","findings":[
                  {"severity":"PASS","area":"API","message":"GET /api/v1/tasks 구현이 API_SPEC과 일치합니다."}
                ]}
                """);
        when(ragPipelineClient.reviewPullRequestConsistency(any())).thenReturn(agentResponse);
        when(githubClient.createCompletedConsistencyCheckRun(eq("timiroom/timiroom-backend"), eq(INSTALLATION_ID),
                eq("head-sha"), eq(100), eq(false), any())).thenReturn(new GithubCheckRunInfo(9L, "https://check", "success"));
        when(githubClient.createPullRequestCommentReview(eq("timiroom/timiroom-backend"), eq(INSTALLATION_ID),
                eq(42), eq("head-sha"), any())).thenReturn(new GithubPullRequestReviewInfo(7L, "https://review", "COMMENTED"));

        var result = service.checkAndReview(PROJECT_ID, MEMBER_ID, REPO_ID, 42);

        assertThat(result.evaluator()).isEqualTo("SPRING_FOUNDRY");
        assertThat(result.findings()).extracting(finding -> finding.message())
                .containsExactly("GET /api/v1/tasks 구현이 API_SPEC과 일치합니다.");
        ArgumentCaptor<Object> request = ArgumentCaptor.forClass(Object.class);
        verify(ragPipelineClient).reviewPullRequestConsistency(request.capture());
        assertThat(((Map<?, ?>) request.getValue()).get("repository")).isEqualTo("timiroom/timiroom-backend");
        verify(consistencyServiceClient, never()).reviewPullRequestConsistency(any());
    }

    @Test
    void checkAndReview_Python_국내_EXAONE_Agent_판정을_사용한다() throws Exception {
        givenLinkedPm();
        ReflectionTestUtils.setField(service, "agentEnabled", true);
        ReflectionTestUtils.setField(service, "agentModel", "gpt-5.4-mini");
        ReflectionTestUtils.setField(service, "agentRuntime", "PYTHON");
        when(pipelineService.getLatestArtifactsByProject(PROJECT_ID)).thenReturn(List.of(
                PipelineArtifact.builder().artifactType(PipelineArtifact.ArtifactType.API_SPEC)
                        .content("GET /api/v1/tasks").build()));
        when(reviewRecordRepository.findByProjectIdAndGithubRepoIdAndPullNumber(PROJECT_ID, REPO_ID, 42))
                .thenReturn(Optional.empty());
        var agentResponse = new ObjectMapper().readTree("""
                {"agent":"PR_CONSISTENCY_AGENT","provider":"EXAONE","evaluationMode":"EXAONE_FACT_GATE","summary":"API 명세와 구현이 일치합니다.","findings":[
                  {"severity":"PASS","area":"API","message":"@GetMapping EXAONE 검증 결과 API_SPEC과 일치합니다.",
                   "evidence":["@GetMapping으로 GET /api/v1/tasks 구현"],
                   "references":[{"sourceType":"IMPLEMENTATION","source":"TaskController.java","line":12,"quote":"@GetMapping('/api/v1/tasks')"}],
                   "recommendation":"현재 구현을 유지하세요."}
                ]}
                """);
        when(consistencyServiceClient.reviewPullRequestConsistency(any())).thenReturn(agentResponse);
        when(githubClient.createCompletedConsistencyCheckRun(eq("timiroom/timiroom-backend"), eq(INSTALLATION_ID),
                eq("head-sha"), eq(100), eq(false), any())).thenReturn(new GithubCheckRunInfo(9L, "https://check", "success"));
        when(githubClient.createPullRequestCommentReview(eq("timiroom/timiroom-backend"), eq(INSTALLATION_ID),
                eq(42), eq("head-sha"), any())).thenReturn(new GithubPullRequestReviewInfo(7L, "https://review", "COMMENTED"));

        var result = service.checkAndReview(PROJECT_ID, MEMBER_ID, REPO_ID, 42);

        assertThat(result.evaluator()).isEqualTo("PYTHON_EXAONE_FACT_GATE");
        assertThat(result.findings()).extracting(finding -> finding.message())
                .containsExactly("@GetMapping EXAONE 검증 결과 API_SPEC과 일치합니다.");
        assertThat(result.findings().getFirst().evidence()).containsExactly("@GetMapping으로 GET /api/v1/tasks 구현");
        assertThat(result.findings().getFirst().recommendation()).isEqualTo("현재 구현을 유지하세요.");
        assertThat(result.findings().getFirst().references()).hasSize(1);
        assertThat(result.findings().getFirst().references().getFirst().line()).isEqualTo(12);
        ArgumentCaptor<String> reviewBody = ArgumentCaptor.forClass(String.class);
        verify(githubClient).createPullRequestCommentReview(eq("timiroom/timiroom-backend"), eq(INSTALLATION_ID),
                eq(42), eq("head-sha"), reviewBody.capture());
        assertThat(reviewBody.getValue()).contains("`@GetMapping` EXAONE 검증 결과 API_SPEC과 일치합니다.");
        verify(consistencyServiceClient).reviewPullRequestConsistency(any());
        verify(ragPipelineClient, never()).reviewPullRequestConsistency(any());
    }

    @Test
    void checkAndReview_Python_분석요청에_head와_base_전체파일을_제한적으로_포함한다() throws Exception {
        givenLinkedPm();
        ReflectionTestUtils.setField(service, "agentEnabled", true);
        ReflectionTestUtils.setField(service, "agentRuntime", "PYTHON");
        when(pipelineService.getLatestArtifactsByProject(PROJECT_ID)).thenReturn(List.of(
                PipelineArtifact.builder().artifactType(PipelineArtifact.ArtifactType.API_SPEC)
                        .content("GET /api/v1/tasks").build()));
        when(reviewRecordRepository.findByProjectIdAndGithubRepoIdAndPullNumber(PROJECT_ID, REPO_ID, 42))
                .thenReturn(Optional.empty());
        when(githubClient.getRepositoryFileContent("timiroom/timiroom-backend", INSTALLATION_ID,
                "TaskController.java", "head-sha")).thenReturn(Optional.of("head source"));
        when(githubClient.getRepositoryFileContent("timiroom/timiroom-backend", INSTALLATION_ID,
                "TaskController.java", "develop")).thenReturn(Optional.of("base source"));
        when(consistencyServiceClient.reviewPullRequestConsistency(any())).thenReturn(new ObjectMapper().readTree("""
                {"evaluationMode":"EXAONE_FACT_GATE","summary":"일치","findings":[
                  {"severity":"PASS","area":"API","message":"일치","evidence":[]}
                ]}
                """));
        when(githubClient.createCompletedConsistencyCheckRun(eq("timiroom/timiroom-backend"), eq(INSTALLATION_ID),
                eq("head-sha"), eq(100), eq(false), any())).thenReturn(new GithubCheckRunInfo(9L, "https://check", "success"));
        when(githubClient.createPullRequestCommentReview(eq("timiroom/timiroom-backend"), eq(INSTALLATION_ID),
                eq(42), eq("head-sha"), any())).thenReturn(new GithubPullRequestReviewInfo(7L, "https://review", "COMMENTED"));

        service.checkAndReview(PROJECT_ID, MEMBER_ID, REPO_ID, 42);

        ArgumentCaptor<Object> request = ArgumentCaptor.forClass(Object.class);
        verify(consistencyServiceClient).reviewPullRequestConsistency(request.capture());
        var changedFiles = (List<?>) ((Map<?, ?>) request.getValue()).get("changedFiles");
        var changedFile = (Map<?, ?>) changedFiles.getFirst();
        assertThat(changedFile.get("content")).isEqualTo("head source");
        assertThat(changedFile.get("baseContent")).isEqualTo("base source");
        assertThat(changedFile.get("patchTruncated")).isEqualTo(false);
    }

    @Test
    void checkAndReview_근거부족은_통과가_아니라_판정보류_0점이다() throws Exception {
        givenLinkedPm();
        ReflectionTestUtils.setField(service, "agentEnabled", true);
        ReflectionTestUtils.setField(service, "agentRuntime", "PYTHON");
        when(pipelineService.getLatestArtifactsByProject(PROJECT_ID)).thenReturn(List.of());
        when(reviewRecordRepository.findByProjectIdAndGithubRepoIdAndPullNumber(PROJECT_ID, REPO_ID, 42))
                .thenReturn(Optional.empty());
        when(consistencyServiceClient.reviewPullRequestConsistency(any())).thenReturn(new ObjectMapper().readTree("""
                {"evaluationMode":"EXAONE_FACT_GATE","summary":"판정 보류","findings":[
                  {"severity":"INCONCLUSIVE","area":"Fact Gate","message":"근거 부족","evidence":[]}
                ]}
                """));
        when(githubClient.createCompletedConsistencyCheckRun(eq("timiroom/timiroom-backend"), eq(INSTALLATION_ID),
                eq("head-sha"), eq(0), eq(true), any())).thenReturn(new GithubCheckRunInfo(9L, "https://check", "neutral"));
        when(githubClient.createPullRequestCommentReview(eq("timiroom/timiroom-backend"), eq(INSTALLATION_ID),
                eq(42), eq("head-sha"), any())).thenReturn(new GithubPullRequestReviewInfo(7L, "https://review", "COMMENTED"));
        when(projectMemberRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of());

        var result = service.checkAndReview(PROJECT_ID, MEMBER_ID, REPO_ID, 42);

        assertThat(result.score()).isZero();
        assertThat(result.findings().getFirst().severity()).isEqualTo("INCONCLUSIVE");
        ArgumentCaptor<String> reviewBody = ArgumentCaptor.forClass(String.class);
        verify(githubClient).createPullRequestCommentReview(eq("timiroom/timiroom-backend"), eq(INSTALLATION_ID),
                eq(42), eq("head-sha"), reviewBody.capture());
        assertThat(reviewBody.getValue()).contains("판정 보류").contains("**—**");
    }

    @Test
    void checkAndReview_Agent_실패시_규칙_엔진으로_fallback한다() {
        givenLinkedPm();
        ReflectionTestUtils.setField(service, "agentEnabled", true);
        ReflectionTestUtils.setField(service, "agentRuntime", "SPRING");
        when(pipelineService.getLatestArtifactsByProject(PROJECT_ID)).thenReturn(List.of(
                PipelineArtifact.builder().artifactType(PipelineArtifact.ArtifactType.API_SPEC)
                        .content("GET /api/v1/tasks").build()));
        when(reviewRecordRepository.findByProjectIdAndGithubRepoIdAndPullNumber(PROJECT_ID, REPO_ID, 42))
                .thenReturn(Optional.empty());
        when(ragPipelineClient.reviewPullRequestConsistency(any()))
                .thenThrow(new IllegalStateException("agent unavailable"));
        when(githubClient.createCompletedConsistencyCheckRun(eq("timiroom/timiroom-backend"), eq(INSTALLATION_ID),
                eq("head-sha"), eq(100), eq(false), any())).thenReturn(new GithubCheckRunInfo(9L, "https://check", "success"));
        when(githubClient.createPullRequestCommentReview(eq("timiroom/timiroom-backend"), eq(INSTALLATION_ID),
                eq(42), eq("head-sha"), any())).thenReturn(new GithubPullRequestReviewInfo(7L, "https://review", "COMMENTED"));

        var result = service.checkAndReview(PROJECT_ID, MEMBER_ID, REPO_ID, 42);

        assertThat(result.evaluator()).isEqualTo("RULE_FALLBACK");
        assertThat(result.findings()).anyMatch(finding -> finding.message().contains("규칙 기반 검사"));
    }

    @Test
    void checkAndReview_경고가_있으면_프로젝트_멤버에게_알림을_만든다() {
        givenLinkedPm();
        when(pipelineService.getLatestArtifactsByProject(PROJECT_ID)).thenReturn(List.of());
        when(reviewRecordRepository.findByProjectIdAndGithubRepoIdAndPullNumber(PROJECT_ID, REPO_ID, 42))
                .thenReturn(Optional.empty());
        when(githubClient.createCompletedConsistencyCheckRun(eq("timiroom/timiroom-backend"), eq(INSTALLATION_ID),
                eq("head-sha"), eq(75), eq(false), any())).thenReturn(new GithubCheckRunInfo(9L, "https://check", "neutral"));
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

    @Test
    void list_같은_head_sha의_기존_정합성_결과를_함께_반환한다() {
        when(projectRepoLinkRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(
                ProjectRepoLink.builder().projectId(PROJECT_ID).githubRepoId(REPO_ID).build()));
        when(githubRepoRepository.findById(REPO_ID)).thenReturn(Optional.of(GithubRepo.builder().id(REPO_ID)
                .fullName("timiroom/timiroom-backend").installationId(INSTALLATION_ID).build()));
        when(githubClient.listPullRequests("timiroom/timiroom-backend", INSTALLATION_ID)).thenReturn(List.of(
                new GithubPullRequestInfo(42, "feat: login #24", "", "open", false, "sha1", "feature/24-login",
                        "develop", "https://backend/pr/42", "dev", "2026-07-12T10:00:00Z")));
        when(reviewRecordRepository.findByProjectIdAndGithubRepoIdIn(PROJECT_ID, List.of(REPO_ID)))
                .thenReturn(List.of(GithubPullRequestReviewRecord.builder()
                        .projectId(PROJECT_ID).githubRepoId(REPO_ID).pullNumber(42).headSha("sha1")
                        .reviewUrl("https://review").checkRunUrl("https://check").score(75)
                        .evaluator("PYTHON_EXAONE_FACT_GATE")
                        .findingsJson("[{\"severity\":\"WARNING\",\"area\":\"API\",\"message\":\"메서드 불일치\"}]")
                        .build()));

        var pulls = service.list(PROJECT_ID, MEMBER_ID);

        assertThat(pulls).hasSize(1);
        assertThat(pulls.getFirst().consistencyResult()).isNotNull();
        assertThat(pulls.getFirst().consistencyResult().score()).isEqualTo(75);
        assertThat(pulls.getFirst().consistencyResult().evaluator()).isEqualTo("PYTHON_EXAONE_FACT_GATE");
        assertThat(pulls.getFirst().consistencyResult().findings()).hasSize(1);
    }
}

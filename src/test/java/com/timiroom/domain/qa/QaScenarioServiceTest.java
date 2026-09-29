package com.timiroom.domain.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.github.GithubPullRequestReviewRecordRepository;
import com.timiroom.domain.github.GithubRepoRepository;
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
import com.timiroom.domain.qa.service.QaScenarioService;
import com.timiroom.infra.github.GithubClient;
import com.timiroom.infra.ragpipeline.RagPipelineClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.anyLong;

/**
 * QA 시나리오 생성·실행 검증.
 *
 * run()/generate()가 실제 코드를 돌리지 않고 에이전트 응답을 그대로 구조화하는지,
 * 근거 부족 시 안전한 기본값(NORMAL/INCONCLUSIVE)으로 떨어지는지를 중심으로 본다.
 */
@ExtendWith(MockitoExtension.class)
class QaScenarioServiceTest {

    @Mock ProjectService projectService;
    @Mock PipelineService pipelineService;
    @Mock QaScenarioRepository qaScenarioRepository;
    @Mock ProjectRepoLinkRepository projectRepoLinkRepository;
    @Mock GithubRepoRepository githubRepoRepository;
    @Mock GithubPullRequestReviewRecordRepository reviewRecordRepository;
    @Mock GithubClient githubClient;
    @Mock RagPipelineClient ragPipelineClient;

    QaScenarioService service;

    private static final long PROJECT_ID = 1L;
    private static final long MEMBER_ID = 10L;

    private static final String FEATURE_LIST = """
            [{"name":"회원가입","priority":"P0","description":"이메일로 가입한다","requirements":["이메일 형식 검증","비밀번호 8자 이상"]}]
            """;

    @BeforeEach
    void setUp() {
        service = new QaScenarioService(projectService, pipelineService, qaScenarioRepository,
                projectRepoLinkRepository, githubRepoRepository, reviewRecordRepository,
                githubClient, ragPipelineClient, new ObjectMapper());
    }

    @Test
    void create_제목이_비어있으면_예외() {
        given(projectService.getById(PROJECT_ID, MEMBER_ID)).willReturn(Project.builder().build());

        var request = new CreateQaScenarioRequest("회원가입", "NORMAL", "  ", "g", "w", "결과");

        assertThatThrownBy(() -> service.create(PROJECT_ID, MEMBER_ID, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_사용자_시나리오를_저장하고_USER_출처로_반환한다() {
        given(projectService.getById(PROJECT_ID, MEMBER_ID)).willReturn(Project.builder().build());
        var request = new CreateQaScenarioRequest("회원가입", "boundary", "이메일 길이 경계", "g", "w", "결과");

        QaScenarioResponse response = service.create(PROJECT_ID, MEMBER_ID, request);

        assertThat(response.source()).isEqualTo("USER");
        assertThat(response.type()).isEqualTo("BOUNDARY");
        assertThat(response.title()).isEqualTo("이메일 길이 경계");
        assertThat(response.verdict()).isNull();
    }

    @Test
    void generate_기능_명세에_없는_기능이면_예외() {
        given(projectService.getById(PROJECT_ID, MEMBER_ID)).willReturn(
                Project.builder().projectName("테스트 프로젝트").build());
        given(pipelineService.getLatestArtifactsByProject(PROJECT_ID)).willReturn(List.of(
                PipelineArtifact.builder().artifactType(PipelineArtifact.ArtifactType.FEATURE_LIST).content(FEATURE_LIST).build()));

        assertThatThrownBy(() -> service.generate(PROJECT_ID, MEMBER_ID, new GenerateQaScenarioRequest("없는기능")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generate_에이전트_응답을_시나리오로_저장한다() throws Exception {
        given(projectService.getById(PROJECT_ID, MEMBER_ID)).willReturn(
                Project.builder().projectName("테스트 프로젝트").build());
        given(pipelineService.getLatestArtifactsByProject(PROJECT_ID)).willReturn(List.of(
                PipelineArtifact.builder().artifactType(PipelineArtifact.ArtifactType.FEATURE_LIST).content(FEATURE_LIST).build()));

        String agentJson = """
                {"scenarios":[
                  {"type":"normal","title":"정상 가입","given":"유효 이메일","when":"가입 요청","then":"계정 생성"},
                  {"type":"exception","title":"중복 이메일","given":"이미 가입된 이메일","when":"가입 요청","then":"오류 응답"}
                ]}
                """;
        given(ragPipelineClient.generateQaScenarios(any())).willReturn(new ObjectMapper().readTree(agentJson));
        given(qaScenarioRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        List<QaScenarioResponse> responses = service.generate(PROJECT_ID, MEMBER_ID, new GenerateQaScenarioRequest("회원가입"));

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).source()).isEqualTo("AI");
        assertThat(responses.get(0).type()).isEqualTo("NORMAL");
        assertThat(responses.get(1).type()).isEqualTo("EXCEPTION");
    }

    @Test
    void run_다른_프로젝트_시나리오는_거부한다() {
        given(projectService.getById(PROJECT_ID, MEMBER_ID)).willReturn(Project.builder().build());
        QaScenario foreignScenario = QaScenario.builder().projectId(999L).build();
        given(qaScenarioRepository.findById(5L)).willReturn(Optional.of(foreignScenario));

        assertThatThrownBy(() -> service.run(PROJECT_ID, MEMBER_ID, 5L))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void run_관련_코드가_없어도_에이전트_판정을_그대로_반영한다() throws Exception {
        given(projectService.getById(PROJECT_ID, MEMBER_ID)).willReturn(
                Project.builder().projectName("테스트 프로젝트").build());
        QaScenario scenario = QaScenario.builder()
                .projectId(PROJECT_ID).featureName("회원가입").type(QaScenario.Type.NORMAL)
                .title("정상 가입").given("g").whenStep("w").thenResult("계정 생성")
                .source(QaScenario.Source.USER).build();
        given(qaScenarioRepository.findById(5L)).willReturn(Optional.of(scenario));
        given(projectRepoLinkRepository.findByProjectId(PROJECT_ID)).willReturn(List.of());

        String agentJson = """
                {"verdict":"pass","reasoning":"API 명세에 회원가입 엔드포인트가 있습니다","evidence":["POST /api/v1/users"]}
                """;
        given(ragPipelineClient.evaluateQaScenario(any())).willReturn(new ObjectMapper().readTree(agentJson));

        QaScenarioResponse response = service.run(PROJECT_ID, MEMBER_ID, 5L);

        assertThat(response.verdict()).isEqualTo("PASS");
        assertThat(response.evidence()).containsExactly("POST /api/v1/users");
        assertThat(response.evaluatedAt()).isNotNull();
    }

    @Test
    void run_알수없는_verdict는_INCONCLUSIVE로_저장한다() throws Exception {
        given(projectService.getById(PROJECT_ID, MEMBER_ID)).willReturn(
                Project.builder().projectName("테스트 프로젝트").build());
        QaScenario scenario = QaScenario.builder()
                .projectId(PROJECT_ID).type(QaScenario.Type.NORMAL).title("t")
                .source(QaScenario.Source.USER).build();
        given(qaScenarioRepository.findById(5L)).willReturn(Optional.of(scenario));
        given(projectRepoLinkRepository.findByProjectId(PROJECT_ID)).willReturn(List.of());
        given(ragPipelineClient.evaluateQaScenario(any()))
                .willReturn(new ObjectMapper().readTree("{\"verdict\":\"maybe\",\"reasoning\":\"불명확\"}"));

        QaScenarioResponse response = service.run(PROJECT_ID, MEMBER_ID, 5L);

        assertThat(response.verdict()).isEqualTo("INCONCLUSIVE");
    }

    @Test
    void delete_다른_프로젝트_시나리오는_거부한다() {
        given(projectService.getById(PROJECT_ID, MEMBER_ID)).willReturn(Project.builder().build());
        given(qaScenarioRepository.findById(5L)).willReturn(Optional.of(QaScenario.builder().projectId(999L).build()));

        assertThatThrownBy(() -> service.delete(PROJECT_ID, MEMBER_ID, 5L))
                .isInstanceOf(SecurityException.class);
    }
}

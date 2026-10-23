package com.timiroom.domain.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.notification.service.NotificationService;
import com.timiroom.domain.pipeline.entity.PipelineArtifact;
import com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType;
import com.timiroom.domain.pipeline.entity.PipelineExecution;
import com.timiroom.domain.pipeline.entity.PipelineExecution.ExecutionStatus;
import com.timiroom.domain.pipeline.repository.ArtifactRevisionRepository;
import com.timiroom.domain.pipeline.repository.PipelineArtifactRepository;
import com.timiroom.domain.pipeline.repository.PipelineExecutionRepository;
import com.timiroom.domain.pipeline.service.PipelineService;
import com.timiroom.domain.project.entity.Project;
import com.timiroom.domain.project.entity.mapping.ProjectMember;
import com.timiroom.domain.project.enums.ProjectRole;
import com.timiroom.domain.project.repository.ProjectMemberRepository;
import com.timiroom.domain.project.repository.ProjectRepository;
import com.timiroom.domain.project.service.ProjectService;
import com.timiroom.domain.requirement.entity.Requirement;
import com.timiroom.domain.requirement.repository.RequirementRepository;
import com.timiroom.domain.requirement.service.RequirementService;
import com.timiroom.domain.spec.service.ArtifactWriteService;
import com.timiroom.domain.spec.service.DocumentAccessService;
import com.timiroom.domain.team.service.TeamService;
import com.timiroom.infra.ragpipeline.RagPipelineClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** 문서 저장(PATCH documents)과 조회(GET documents, GET pipeline artifacts)가 같은 실행을 보도록 보장한다. */
class ProjectDocumentSaveTest {
    static final long PROJECT = 5L, MEMBER = 8L, REQUIREMENT = 30L;

    ProjectRepository projects = mock(ProjectRepository.class);
    ProjectMemberRepository members = mock(ProjectMemberRepository.class);
    RequirementRepository requirements = mock(RequirementRepository.class);
    PipelineExecutionRepository executions = mock(PipelineExecutionRepository.class);
    PipelineArtifactRepository artifacts = mock(PipelineArtifactRepository.class);
    TeamService teams = mock(TeamService.class);
    RequirementService requirementService = mock(RequirementService.class);
    List<PipelineArtifact> stored = new ArrayList<>();
    ProjectService service;
    PipelineService pipeline;

    @BeforeEach
    void fixture() {
        var access = new DocumentAccessService(projects, members, executions, requirements, teams);
        service = new ProjectService(projects, members, requirements, executions, artifacts, teams,
                new ArtifactWriteService(artifacts, mock(ArtifactRevisionRepository.class), access),
                mock(com.timiroom.domain.project.service.ProjectDataCleaner.class));
        pipeline = new PipelineService(mock(RagPipelineClient.class), executions, artifacts,
                mock(ArtifactRevisionRepository.class), requirementService, mock(NotificationService.class),
                new ObjectMapper(), mock(ArtifactWriteService.class), mock(DocumentAccessService.class), mock(com.timiroom.domain.usage.service.AiCallService.class));

        when(projects.findById(PROJECT)).thenReturn(Optional.of(
                Project.builder().projectId(PROJECT).teamId(1L).projectName("p").build()));
        when(members.findByProjectIdAndMemberId(PROJECT, MEMBER)).thenReturn(Optional.of(
                ProjectMember.builder().projectId(PROJECT).memberId(MEMBER).projectRole(ProjectRole.PM).build()));
        Requirement requirement = Requirement.builder().requirementId(REQUIREMENT).projectId(PROJECT).build();
        when(requirements.findByProjectIdOrderByCreatedAtDesc(PROJECT)).thenReturn(List.of(requirement));
        when(requirements.findById(REQUIREMENT)).thenReturn(Optional.of(requirement));
        when(requirementService.getByProject(PROJECT)).thenReturn(List.of(requirement));
        when(executions.findCurrentCompleted(anyList())).thenCallRealMethod();

        // 저장된 artifact를 메모리에 두고 조회 쿼리가 그대로 읽게 한다.
        when(artifacts.save(any())).thenAnswer(i -> {
            PipelineArtifact a = i.getArgument(0);
            if (stored.stream().noneMatch(x -> x == a)) stored.add(a);
            return a;
        });
        when(artifacts.findByExecutionIdsAndType(anyList(), any())).thenAnswer(i -> {
            List<Long> ids = i.getArgument(0);
            ArtifactType type = i.getArgument(1);
            return stored.stream().filter(a -> ids.contains(a.getExecutionId()) && a.getArtifactType() == type).toList();
        });
        when(artifacts.findByExecutionIdOrderByArtifactType(anyLong())).thenAnswer(i -> {
            Long id = i.getArgument(0);
            return stored.stream().filter(a -> a.getExecutionId().equals(id)).toList();
        });
    }

    private void givenExecutions(PipelineExecution... newestFirst) {
        when(executions.findByRequirementIdOrderByCreatedAtDesc(REQUIREMENT)).thenReturn(List.of(newestFirst));
        for (PipelineExecution e : newestFirst) {
            when(executions.findById(e.getExecutionId())).thenReturn(Optional.of(e));
        }
    }

    private static PipelineExecution execution(long id, ExecutionStatus status) {
        return PipelineExecution.builder().executionId(id).requirementId(REQUIREMENT).status(status).build();
    }

    @Test
    void 실행_이력이_없으면_409로_거절하고_아무것도_저장하지_않는다() {
        givenExecutions();

        assertThatThrownBy(() -> service.saveDocument(PROJECT, MEMBER, ArtifactType.PRD, "# PRD"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("완료된 파이프라인 실행이 없어");
        verify(artifacts, never()).save(any());
    }

    @Test
    void 실패한_실행만_있으면_고아_문서를_만들지_않고_409로_거절한다() {
        givenExecutions(execution(9L, ExecutionStatus.FAILED), execution(8L, ExecutionStatus.FAILED),
                execution(7L, ExecutionStatus.FAILED));

        assertThatThrownBy(() -> service.saveDocument(PROJECT, MEMBER, ArtifactType.PRD, "# PRD"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("완료된 파이프라인 실행이 없어");
        verify(artifacts, never()).save(any());
        assertThat(pipeline.getLatestArtifactsByProject(PROJECT)).isEmpty();
    }

    @Test
    void 완료된_실행이_있으면_그_실행에_저장하고_두_조회_경로_모두에서_보인다() {
        // 최신 실행은 실패, 그 이전이 완료: 완료된 7번이 "현재" 실행이다.
        givenExecutions(execution(9L, ExecutionStatus.FAILED), execution(7L, ExecutionStatus.COMPLETED));

        PipelineArtifact saved = service.saveDocument(PROJECT, MEMBER, ArtifactType.PRD, "# PRD");

        assertThat(saved.getExecutionId()).isEqualTo(7L);
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(service.getDocument(PROJECT, MEMBER, ArtifactType.PRD)).get()
                .extracting(PipelineArtifact::getContent).isEqualTo("# PRD");
        assertThat(pipeline.getLatestArtifactsByProject(PROJECT))
                .singleElement().extracting(PipelineArtifact::getContent).isEqualTo("# PRD");
    }

    @Test
    void 두_번째_저장은_새_artifact를_만들지_않고_같은_문서의_버전을_올린다() {
        givenExecutions(execution(7L, ExecutionStatus.COMPLETED));
        PipelineArtifact first = service.saveDocument(PROJECT, MEMBER, ArtifactType.PRD, "v1");
        ReflectionTestUtils.setField(first, "artifactId", 21L);
        when(artifacts.findForUpdate(21L)).thenReturn(Optional.of(first));

        PipelineArtifact second = service.saveDocument(PROJECT, MEMBER, ArtifactType.PRD, "v2");

        assertThat(stored).hasSize(1);
        assertThat(second.getVersion()).isEqualTo(2);
        assertThat(pipeline.getLatestArtifactsByProject(PROJECT))
                .singleElement().extracting(PipelineArtifact::getContent).isEqualTo("v2");
    }
}

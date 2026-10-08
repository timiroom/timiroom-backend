package com.timiroom.domain.spec;

import com.timiroom.domain.pipeline.entity.PipelineArtifact;
import com.timiroom.domain.pipeline.entity.PipelineExecution;
import com.timiroom.domain.pipeline.repository.PipelineArtifactRepository;
import com.timiroom.domain.pipeline.repository.PipelineExecutionRepository;
import com.timiroom.domain.pipeline.repository.ArtifactRevisionRepository;
import com.timiroom.domain.pipeline.entity.ArtifactRevision;
import com.timiroom.domain.requirement.entity.Requirement;
import com.timiroom.domain.spec.service.ArtifactWriteService;
import com.timiroom.domain.spec.service.DocumentAccessService;
import com.timiroom.domain.project.entity.Project;
import com.timiroom.domain.project.entity.mapping.ProjectMember;
import com.timiroom.domain.project.enums.ProjectRole;
import com.timiroom.domain.project.repository.ProjectRepository;
import com.timiroom.domain.project.repository.ProjectMemberRepository;
import com.timiroom.domain.project.service.ProjectService;
import com.timiroom.domain.requirement.repository.RequirementRepository;
import com.timiroom.domain.team.service.TeamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectDocumentHistoryTest {
    @Mock ProjectRepository projects;
    @Mock ProjectMemberRepository members;
    @Mock RequirementRepository requirements;
    @Mock PipelineExecutionRepository executions;
    @Mock PipelineArtifactRepository artifacts;
    @Mock TeamService teams;
    @Mock ArtifactRevisionRepository revisions;
    ProjectService service;
    PipelineArtifact document;

    @BeforeEach void fixture(){
        var access=new DocumentAccessService(projects,members,executions,requirements,teams);
        service=new ProjectService(projects,members,requirements,executions,artifacts,teams,
            new ArtifactWriteService(artifacts,revisions,access));
        document=PipelineArtifact.builder().artifactId(10L).executionId(20L)
            .artifactType(PipelineArtifact.ArtifactType.API_SPEC).content("{\"path\":\"/items\"}").version(3).build();
        when(projects.findById(1L)).thenReturn(Optional.of(Project.builder().projectId(1L).teamId(5L).projectName("test").build()));
        when(members.findByProjectIdAndMemberId(1L,2L)).thenReturn(Optional.of(ProjectMember.builder().projectId(1L).memberId(2L).projectRole(ProjectRole.PM).build()));
        when(requirements.findRequirementIdsByProjectId(1L)).thenReturn(List.of(30L));
        when(executions.findByRequirementIdIn(List.of(30L))).thenReturn(List.of(PipelineExecution.builder().executionId(20L).status(PipelineExecution.ExecutionStatus.COMPLETED).build()));
        when(artifacts.findByExecutionIdsAndType(List.of(20L),PipelineArtifact.ArtifactType.API_SPEC)).thenReturn(List.of(document));
        when(artifacts.findForUpdate(10L)).thenReturn(Optional.of(document));
        when(executions.findById(20L)).thenReturn(Optional.of(PipelineExecution.builder().executionId(20L).requirementId(30L).build()));
        when(requirements.findById(30L)).thenReturn(Optional.of(Requirement.builder().requirementId(30L).projectId(1L).build()));
    }

    @Test void sameContentDoesNotCreateAnotherVersion(){
        var result=service.saveDocument(1L,2L,PipelineArtifact.ArtifactType.API_SPEC,document.getContent());
        assertThat(result.getVersion()).isEqualTo(3);
        assertThat(result.getContent()).isEqualTo("{\"path\":\"/items\"}");
    }

    @Test void projectSavePreservesPreviousContent(){
        var history=new java.util.ArrayList<ArtifactRevision>();
        when(revisions.save(any())).thenAnswer(i->{ArtifactRevision r=i.getArgument(0);history.add(r);return r;});
        when(artifacts.save(document)).thenReturn(document);
        var result=service.saveDocument(1L,2L,PipelineArtifact.ArtifactType.API_SPEC,"new");
        assertThat(result.getVersion()).isEqualTo(4);
        assertThat(history).singleElement().satisfies(r->{
            assertThat(r.getContent()).isEqualTo("{\"path\":\"/items\"}");
            assertThat(r.getVersion()).isEqualTo(3);
        });
    }
}

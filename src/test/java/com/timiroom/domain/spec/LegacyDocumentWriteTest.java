package com.timiroom.domain.spec;
import com.timiroom.domain.spec.service.*;
import com.timiroom.domain.spec.repository.SpecSnapshotRepository;
import com.timiroom.domain.pipeline.service.PipelineService;
import com.timiroom.domain.pipeline.repository.PipelineArtifactRepository;
import com.timiroom.domain.project.service.ProjectService;
import com.timiroom.domain.project.repository.ProjectRepository;
import com.timiroom.domain.project.entity.Project;
import com.timiroom.domain.spec.entity.SpecSnapshot;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType.*;

class LegacyDocumentWriteTest {
    @Test void publishedDocumentCannotBypassProposalThroughOldSaveRoute() {
        var pipeline=mock(PipelineService.class);var projects=mock(ProjectService.class);var projectRows=mock(ProjectRepository.class);
        var artifacts=mock(PipelineArtifactRepository.class);var access=mock(DocumentAccessService.class);var snapshots=mock(SpecSnapshotRepository.class);
        var service=new LegacyDocumentWriteService(pipeline,projects,projectRows,artifacts,access,snapshots);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"enabled",true);
        when(projectRows.findForUpdate(1L)).thenReturn(Optional.of(Project.builder().projectId(1L).build()));
        when(snapshots.findFirstByProjectIdOrderByRevisionDesc(1L)).thenReturn(Optional.of(new SpecSnapshot(1L,1,2L,"[]")));
        assertThatThrownBy(()->service.project(1L,2L,API_SPEC,"{}"))
            .isInstanceOf(IllegalStateException.class).hasMessage("SPEC_CHANGE_REQUIRED");
        verifyNoInteractions(projects,pipeline);
        when(snapshots.findFirstByProjectIdOrderByRevisionDesc(1L)).thenReturn(Optional.empty());
        service.project(1L,2L,API_SPEC,"{}");verify(projects).saveDocument(1L,2L,API_SPEC,"{}");
    }
}

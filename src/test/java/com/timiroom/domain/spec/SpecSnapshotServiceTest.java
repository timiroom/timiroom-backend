package com.timiroom.domain.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.pipeline.entity.PipelineArtifact;
import com.timiroom.domain.pipeline.service.PipelineService;
import com.timiroom.domain.pipeline.repository.PipelineArtifactRepository;
import com.timiroom.domain.project.entity.Project;
import com.timiroom.domain.project.repository.ProjectRepository;
import com.timiroom.domain.spec.repository.SpecSnapshotRepository;
import com.timiroom.domain.spec.service.DocumentAccessService;
import com.timiroom.domain.spec.service.SpecSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpecSnapshotServiceTest {
    final ProjectRepository projects=mock(ProjectRepository.class);
    final PipelineService pipeline=mock(PipelineService.class);
    final PipelineArtifactRepository artifacts=mock(PipelineArtifactRepository.class);
    final SpecSnapshotRepository snapshots=mock(SpecSnapshotRepository.class);
    final DocumentAccessService access=mock(DocumentAccessService.class);
    SpecSnapshotService service;
    List<PipelineArtifact> docs;
    @BeforeEach void setup(){
        service=new SpecSnapshotService(projects,pipeline,artifacts,snapshots,access,new ObjectMapper().findAndRegisterModules());
        docs=List.of(doc(10,PipelineArtifact.ArtifactType.PRD,"prd"),doc(11,PipelineArtifact.ArtifactType.API_SPEC,"api"),doc(12,PipelineArtifact.ArtifactType.DB_SCHEMA,"db"));
        lenient().when(projects.findForUpdate(1L)).thenReturn(Optional.of(Project.builder().projectId(1L).build()));
        lenient().when(pipeline.getLatestArtifactsByProject(1L)).thenReturn(docs);
        lenient().when(snapshots.findFirstByProjectIdOrderByRevisionDesc(1L)).thenReturn(Optional.empty());
        lenient().when(snapshots.save(any())).thenAnswer(i->i.getArgument(0));
        for(var d:docs) lenient().when(artifacts.findForUpdate(d.getArtifactId())).thenReturn(Optional.of(d));
    }
    @Test void snapshotRemainsUnchangedAfterEdit(){
        var published=service.publish(1L,2L);
        docs.get(1).updateContent("changed");
        assertThat(published.documents()).filteredOn(d->d.type()==PipelineArtifact.ArtifactType.API_SPEC)
            .singleElement().satisfies(d->{assertThat(d.content()).isEqualTo("api");assertThat(d.version()).isEqualTo(1);});
        assertThat(published.revision()).isEqualTo(1);
        assertThat(published.snapshotId()).isNotNull();
    }
    @Test void missingRequiredDocumentCannotPublish(){
        when(pipeline.getLatestArtifactsByProject(1L)).thenReturn(docs.subList(0,2));
        assertThatThrownBy(()->service.publish(1L,2L)).hasMessageContaining("SPEC_NOT_PUBLISHED");
    }
    @Test void revokedActorCannotReadOldSnapshot(){
        doThrow(new SecurityException("ACCESS_DENIED")).when(access).requireRead(1L,2L);
        assertThatThrownBy(()->service.get(1L,2L,UUID.randomUUID())).isInstanceOf(SecurityException.class);
    }
    @Test void nonPmCannotPublish(){
        doThrow(new SecurityException("ACCESS_DENIED")).when(access).requirePm(1L,2L);
        assertThatThrownBy(()->service.publish(1L,2L)).isInstanceOf(SecurityException.class);
    }
    private PipelineArtifact doc(long id,PipelineArtifact.ArtifactType type,String content){
        return PipelineArtifact.builder().artifactId(id).executionId(20L).artifactType(type).content(content).version(1).build();
    }
}

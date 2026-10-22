package com.timiroom.domain.spec;

import com.timiroom.domain.pipeline.entity.ArtifactRevision;
import com.timiroom.domain.pipeline.entity.PipelineArtifact;
import com.timiroom.domain.pipeline.repository.ArtifactRevisionRepository;
import com.timiroom.domain.pipeline.repository.PipelineArtifactRepository;
import com.timiroom.domain.spec.dto.ArtifactWriteCommand;
import com.timiroom.domain.spec.service.ArtifactWriteService;
import com.timiroom.domain.spec.service.DocumentAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArtifactWriteServiceTest {
    final PipelineArtifactRepository artifacts=mock(PipelineArtifactRepository.class);
    final ArtifactRevisionRepository revisions=mock(ArtifactRevisionRepository.class);
    final DocumentAccessService access=mock(DocumentAccessService.class);
    final List<ArtifactRevision> storedHistory=new ArrayList<>();
    ArtifactWriteService service;
    PipelineArtifact document;

    @BeforeEach void setup(){
        service=new ArtifactWriteService(artifacts,revisions,access);
        document=PipelineArtifact.builder().artifactId(10L).executionId(20L)
            .artifactType(PipelineArtifact.ArtifactType.API_SPEC).content("old").version(3).build();
        when(artifacts.findForUpdate(10L)).thenReturn(Optional.of(document));
        lenient().when(artifacts.save(any())).thenAnswer(i->i.getArgument(0));
        lenient().when(revisions.save(any())).thenAnswer(i->{ArtifactRevision r=i.getArgument(0); storedHistory.add(r); return r;});
    }
    @Test void sameContentDoesNotCreateRevision(){
        service.write(1L,2L,10L,3,"old");
        assertThat(document.getVersion()).isEqualTo(3);
        assertThat(storedHistory).isEmpty();
    }
    @Test void writePreservesPreviousContentAndVersion(){
        service.write(1L,2L,10L,3,"new");
        assertThat(document.getContent()).isEqualTo("new");
        assertThat(document.getVersion()).isEqualTo(4);
        assertThat(storedHistory).singleElement().satisfies(r->{
            assertThat(r.getArtifactId()).isEqualTo(10L);
            assertThat(r.getContent()).isEqualTo("old");
            assertThat(r.getVersion()).isEqualTo(3);
        });
    }
    @Test void staleVersionDoesNotOverwrite(){
        assertThatThrownBy(()->service.write(1L,2L,10L,2,"new")).hasMessageContaining("SPEC_CONFLICT");
        assertThat(document.getContent()).isEqualTo("old");
        assertThat(storedHistory).isEmpty();
    }
    @Test void deniedActorCannotEvenSaveSameContent(){
        doThrow(new SecurityException("ACCESS_DENIED")).when(access).requireArtifactWrite(1L,2L,document);
        assertThatThrownBy(()->service.write(1L,2L,10L,3,"old")).isInstanceOf(SecurityException.class);
        assertThat(storedHistory).isEmpty();
    }
    @Test void nullContentIsRejected(){
        assertThatThrownBy(()->service.write(1L,2L,10L,3,null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(document.getContent()).isEqualTo("old");
    }
    @Test void batchChecksAllVersionsBeforeAnyMutation(){
        var other=PipelineArtifact.builder().artifactId(11L).executionId(20L)
            .artifactType(PipelineArtifact.ArtifactType.DB_SCHEMA).content("db").version(2).build();
        when(artifacts.findForUpdate(11L)).thenReturn(Optional.of(other));
        assertThatThrownBy(()->service.writeBatch(1L,2L,List.of(
            new ArtifactWriteCommand(10L,3,null,"new"),new ArtifactWriteCommand(11L,1,null,"bad"))))
            .hasMessageContaining("SPEC_CONFLICT");
        assertThat(document.getContent()).isEqualTo("old");
        assertThat(other.getContent()).isEqualTo("db");
        assertThat(storedHistory).isEmpty();
    }
}

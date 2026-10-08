package com.timiroom.domain.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.spec.dto.*;
import com.timiroom.domain.spec.entity.*;
import com.timiroom.domain.spec.repository.*;
import com.timiroom.domain.spec.service.*;
import com.timiroom.domain.project.repository.ProjectRepository;
import com.timiroom.domain.project.entity.Project;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpecChangeServiceTest {
    @Test void manualProposalPreservesFixedBeforeAndDoesNotSaveOriginal() throws Exception {
        when(snapshots.get(1L,2L,snapshotId)).thenReturn(snapshot());
        when(snapshots.latest(1L,2L)).thenReturn(snapshot());
        when(proposals.save(any())).thenAnswer(i->i.getArgument(0));
        var input=new ChangeInstruction(List.of(API_SPEC),"직접 수정",List.of());
        var document=mapper.readTree("{\"endpoints\":[]}");
        var proposal=service.createManual(1L,2L,snapshotId,input,Map.of(API_SPEC,document),Map.of(API_SPEC,"h2"));
        assertThat(mapper.readTree(proposal.getInputJson()).path("manualDocuments").path("API_SPEC")).isEqualTo(document);
        assertThat(proposal.getState()).isEqualTo(SpecChangeProposal.State.GENERATING);
        assertThatThrownBy(()->service.createManual(1L,2L,snapshotId,input,Map.of(API_SPEC,document),Map.of(API_SPEC,"old")))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(writer);
    }
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final SpecChangeProposalRepository proposals = mock(SpecChangeProposalRepository.class);
    final SpecSnapshotService snapshots = mock(SpecSnapshotService.class);
    final ArtifactWriteService writer = mock(ArtifactWriteService.class);
    final DocumentAccessService access = mock(DocumentAccessService.class);
    final ProjectRepository projects = mock(ProjectRepository.class);
    final ArtifactReviewResultReader reviews = mock(ArtifactReviewResultReader.class);
    final SpecChangeService service = new SpecChangeService(proposals, snapshots, writer, access, projects, reviews, mapper);
    final UUID snapshotId = UUID.randomUUID();
    SpecSnapshotDto snapshot() { return new SpecSnapshotDto(snapshotId, 1L, 1, 2L, Instant.now(), List.of(
        new SpecDocumentDto(PRD, 10L, 5L, 1, "h", "{}"), new SpecDocumentDto(API_SPEC, 11L, 5L, 1, "h2", "{}")) ); }
    SpecChangeProposal ready() throws Exception {
        var p = new SpecChangeProposal(1L, 2L, snapshotId, mapper.writeValueAsString(snapshot()), "{}", "request");
        p.ready("{\"API_SPEC\":{\"endpoints\":[]}}", "{}", "{}", "test");
        when(proposals.findForUpdate(p.getProposalId(), 1L)).thenReturn(Optional.of(p));
        when(projects.findForUpdate(1L)).thenReturn(Optional.of(Project.builder().projectId(1L).build()));
        when(snapshots.latest(1L,2L)).thenReturn(snapshot());
        return p;
    }
    @Test void creatingProposalCannotWriteOriginals() {
        when(snapshots.get(1L, 2L, snapshotId)).thenReturn(snapshot());
        when(snapshots.latest(1L,2L)).thenReturn(snapshot());
        when(proposals.save(any())).thenAnswer(i -> i.getArgument(0));
        var p = service.create(1L, 2L, snapshotId, new ChangeInstruction(List.of(API_SPEC), "수정", List.of()));
        assertThat(p.getState()).isEqualTo(SpecChangeProposal.State.GENERATING);
        verifyNoInteractions(writer, reviews);
    }
    @Test void historicalBaselineCannotStartAnotherPaidProposal() {
        when(snapshots.get(1L,2L,snapshotId)).thenReturn(snapshot());
        when(snapshots.latest(1L,2L)).thenReturn(new SpecSnapshotDto(UUID.randomUUID(),1L,2,2L,Instant.now(),snapshot().documents()));
        assertThatThrownBy(()->service.create(1L,2L,snapshotId,new ChangeInstruction(List.of(API_SPEC),"수정",List.of())))
            .hasMessage("SPEC_CONFLICT");
        verifyNoInteractions(proposals,writer);
    }
    @Test void refusesApprovalWithoutBoundReviewPass() throws Exception {
        var p = ready();
        assertThatThrownBy(() -> service.approve(1L, 2L, p.getProposalId(), 1)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(writer);
        assertThat(p.getState()).isEqualTo(SpecChangeProposal.State.READY);
    }
    @Test void approvalValidatesAllSnapshotDocumentsBeforePublishing() throws Exception {
        var p = ready();
        when(reviews.hasPass(p.getProposalId(), 1, p.getResultHash())).thenReturn(true);
        when(snapshots.publish(1L, 2L)).thenReturn(snapshot());
        var result = service.approve(1L, 2L, p.getProposalId(), 1);
        verify(writer).writeBatch(eq(1L), eq(2L), argThat(writes -> writes.size()==2
            && writes.stream().anyMatch(w -> w.artifactId()==10L && w.content().equals("{}"))
            && writes.stream().anyMatch(w -> w.artifactId()==11L && w.expectedHash().equals("h2"))));
        assertThat(p.getState()).isEqualTo(SpecChangeProposal.State.APPROVED);
        assertThat(result.snapshotId()).isEqualTo(snapshotId);
    }
    @Test void rejectsStaleProposalRevisionAndRepeatedApproval() throws Exception {
        var p = ready();
        assertThatThrownBy(() -> service.approve(1L, 2L, p.getProposalId(), 2)).isInstanceOf(IllegalStateException.class);
        p.reject();
        assertThatThrownBy(() -> service.approve(1L, 2L, p.getProposalId(), 1)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(writer);
    }
}

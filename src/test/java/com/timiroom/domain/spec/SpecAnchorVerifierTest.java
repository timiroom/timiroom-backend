package com.timiroom.domain.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.spec.dto.AnchorVerification;
import com.timiroom.domain.spec.dto.AnchorVerification.Status;
import com.timiroom.domain.spec.dto.SpecDocumentDto;
import com.timiroom.domain.spec.entity.SpecAnchor;
import com.timiroom.domain.spec.entity.SpecSnapshot;
import com.timiroom.domain.spec.repository.SpecChangeProposalRepository;
import com.timiroom.domain.spec.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpecAnchorVerifierTest {
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final DocumentAccessService access=mock(DocumentAccessService.class);
    final SpecChangeProposalRepository proposals=mock(SpecChangeProposalRepository.class);
    AnchorTestStore store;
    SpecAnchorRecorder recorder;
    SpecAnchorVerifier verifier;
    SpecSnapshot first, second;

    @BeforeEach void setup() throws Exception {
        store=new AnchorTestStore();
        recorder=new SpecAnchorRecorder(store.anchors,store.snapshots,proposals,mapper);
        verifier=new SpecAnchorVerifier(store.anchors,store.snapshots,access,mapper);
        var docs=List.of(new SpecDocumentDto(PRD,10L,20L,1,DocumentHash.of("prd"),"prd"),new SpecDocumentDto(API_SPEC,11L,20L,1,DocumentHash.of("api"),"api"));
        String json=mapper.writeValueAsString(docs);
        first=store.snapshot(1,json); recorder.record(first,docs,null);
        second=store.snapshot(2,json); recorder.record(second,docs,null);
    }

    @Test void untouchedSnapshotPassesDatabaseChecksAndWaitsForChain() {
        var result=verifier.verify(1L,2L,second.getSnapshotId());
        assertThat(statuses(result)).containsExactly(Status.PASS,Status.PASS,Status.SKIPPED);
        assertThat(result.verified()).isFalse();
    }
    @Test void editedDocumentBodyFailsDocumentCheck() throws Exception {
        var tampered=List.of(new SpecDocumentDto(PRD,10L,20L,1,DocumentHash.of("prd"),"prd 몰래 수정"),new SpecDocumentDto(API_SPEC,11L,20L,1,DocumentHash.of("api"),"api"));
        ReflectionTestUtils.setField(second,"documentsJson",mapper.writeValueAsString(tampered));
        assertThat(statuses(verifier.verify(1L,2L,second.getSnapshotId()))).containsExactly(Status.FAIL,Status.PASS,Status.SKIPPED);
    }
    @Test void changedRevisionMetadataFailsDocumentCheck() {
        ReflectionTestUtils.setField(second,"publishedAt",second.getPublishedAt().plusSeconds(1));
        assertThat(statuses(verifier.verify(1L,2L,second.getSnapshotId())).getFirst()).isEqualTo(Status.FAIL);
    }
    @Test void replacedDigestFailsManifestCheck() {
        ReflectionTestUtils.setField(store.byRevision.get(2),"digest","0".repeat(64));
        assertThat(statuses(verifier.verify(1L,2L,second.getSnapshotId()))).containsExactly(Status.PASS,Status.FAIL,Status.SKIPPED);
    }
    @Test void editedManifestWithoutDigestFailsManifestCheck() {
        var anchor=store.byRevision.get(2);
        ReflectionTestUtils.setField(anchor,"manifestJson",anchor.getManifestJson()
            .replace("\"proposal\":null","\"proposal\":{\"id\":\""+UUID.randomUUID()+"\",\"resultHash\":\"x\",\"revision\":1}"));
        assertThat(statuses(verifier.verify(1L,2L,second.getSnapshotId()))).containsExactly(Status.PASS,Status.FAIL,Status.SKIPPED);
    }
    @Test void rewrittenPreviousRevisionBreaksChain() {
        ReflectionTestUtils.setField(store.byRevision.get(1),"digest","f".repeat(64));
        assertThat(statuses(verifier.verify(1L,2L,second.getSnapshotId())).get(1)).isEqualTo(Status.FAIL);
    }
    @Test void snapshotWithoutAnchorSkipsEverything() {
        var third=store.snapshot(3,first.getDocumentsJson());
        assertThat(statuses(verifier.verify(1L,2L,third.getSnapshotId()))).containsOnly(Status.SKIPPED);
        assertThat(verifier.anchor(1L,2L,third.getSnapshotId()).state()).isEqualTo("NONE");
    }
    @Test void anchorStatusReportsPendingWithoutChain() {
        var dto=verifier.anchor(1L,2L,second.getSnapshotId());
        assertThat(dto.state()).isEqualTo(SpecAnchor.State.PENDING.name());
        assertThat(dto.chainEnabled()).isFalse();
        assertThat(dto.prevDigest()).isEqualTo(store.byRevision.get(1).getDigest());
        assertThat(dto.projectKey()).isEqualTo(SpecManifest.projectKey(1L));
    }
    @Test void manifestDownloadIsStoredBytes() {
        var manifest=verifier.manifest(1L,2L,second.getSnapshotId());
        assertThat(manifest.json()).isEqualTo(store.byRevision.get(2).getManifestJson());
        assertThat(SpecManifest.digest(manifest.json())).isEqualTo(store.byRevision.get(2).getDigest());
    }
    @Test void outsiderCannotVerify() {
        doThrow(new SecurityException("ACCESS_DENIED")).when(access).requireRead(1L,9L);
        assertThatThrownBy(()->verifier.verify(1L,9L,second.getSnapshotId())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->verifier.manifest(1L,9L,second.getSnapshotId())).isInstanceOf(SecurityException.class);
    }
    private List<Status> statuses(AnchorVerification v) { return v.checks().stream().map(AnchorVerification.Check::status).toList(); }
}

package com.timiroom.domain.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.spec.dto.SpecDocumentDto;
import com.timiroom.domain.spec.entity.SpecAnchor;
import com.timiroom.domain.spec.entity.SpecChangeProposal;
import com.timiroom.domain.spec.repository.SpecChangeProposalRepository;
import com.timiroom.domain.spec.service.SpecAnchorRecorder;
import com.timiroom.domain.spec.service.SpecManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpecAnchorRecorderTest {
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final SpecChangeProposalRepository proposals=mock(SpecChangeProposalRepository.class);
    AnchorTestStore store;
    SpecAnchorRecorder recorder;
    List<SpecDocumentDto> docs;
    String docsJson;

    @BeforeEach void setup() throws Exception {
        store=new AnchorTestStore();
        recorder=new SpecAnchorRecorder(store.anchors,store.snapshots,proposals,mapper);
        docs=List.of(new SpecDocumentDto(PRD,10L,20L,1,"h1","prd"),new SpecDocumentDto(API_SPEC,11L,20L,1,"h2","api"));
        docsJson=mapper.writeValueAsString(docs);
        lenient().when(proposals.findByApprovedSnapshotId(any())).thenReturn(Optional.empty());
    }

    @Test void firstRevisionStartsChainAsPendingLiveRecord() {
        var anchor=recorder.record(store.snapshot(1,docsJson),docs,null);
        assertThat(anchor.getPrevDigest()).isNull();
        assertThat(anchor.isBackfilled()).isFalse();
        assertThat(anchor.getState()).isEqualTo(SpecAnchor.State.PENDING);
        assertThat(anchor.getDigest()).isEqualTo(SpecManifest.digest(anchor.getManifestJson()));
    }
    @Test void laterRevisionChainsToPreviousDigest() {
        var first=recorder.record(store.snapshot(1,docsJson),docs,null);
        var second=recorder.record(store.snapshot(2,docsJson),docs,null);
        assertThat(second.getPrevDigest()).isEqualTo(first.getDigest());
        assertThat(second.getManifestJson()).contains("\"prevDigest\":\""+first.getDigest()+"\"");
        assertThat(second.getDigest()).isNotEqualTo(first.getDigest());
    }
    @Test void backfillFillsMissingRevisionsInOrderWithApprovedProposal() {
        store.snapshot(1,docsJson);
        var second=store.snapshot(2,docsJson);
        var proposal=new SpecChangeProposal(1L,2L,UUID.randomUUID(),"{}","{}","수정");
        proposal.ready("{}","[]","{}","manual");
        proposal.approve(second.getSnapshotId());
        when(proposals.findByApprovedSnapshotId(second.getSnapshotId())).thenReturn(Optional.of(proposal));

        recorder.ensureProject(1L);

        var a1=store.byRevision.get(1); var a2=store.byRevision.get(2);
        assertThat(a1.isBackfilled()).isTrue();
        assertThat(a2.isBackfilled()).isTrue();
        assertThat(a2.getPrevDigest()).isEqualTo(a1.getDigest());
        assertThat(a2.getManifestJson()).contains(proposal.getProposalId().toString()).contains(proposal.getResultHash());
        // 그 뒤 새 발행은 백필한 마지막 revision에 이어진다.
        var third=recorder.record(store.snapshot(3,docsJson),docs,null);
        assertThat(third.getPrevDigest()).isEqualTo(a2.getDigest());
        assertThat(third.isBackfilled()).isFalse();
    }
    @Test void backfillDoesNothingWhenAlreadyUpToDate() {
        recorder.record(store.snapshot(1,docsJson),docs,null);
        clearInvocations(store.anchors);
        recorder.ensureProject(1L);
        verify(store.anchors,never()).save(any());
    }
}

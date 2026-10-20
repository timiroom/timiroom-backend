package com.timiroom.domain.spec.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.spec.dto.SpecSnapshotDto;
import com.timiroom.domain.spec.entity.SpecChangeProposal;
import com.timiroom.domain.spec.service.*;
import com.timiroom.infra.consistency.ConsistencyServiceClient;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArtifactReviewCoverageTest {
    @Test void legacyOrIncompletePassCannotAuthorizeApproval() throws Exception {
        var mapper=new ObjectMapper();
        var changes=mock(SpecChangeService.class);var client=mock(ConsistencyServiceClient.class);
        var proposal=new SpecChangeProposal(1L,2L,UUID.randomUUID(),"{}","{}","edit");
        proposal.ready("{}","{}","{}","test");
        when(changes.get(1L,2L,proposal.getProposalId())).thenReturn(proposal);
        when(changes.readBase(proposal)).thenReturn(new SpecSnapshotDto(proposal.getSnapshotId(),1L,1,2L,Instant.now(),List.of()));
        var service=new ArtifactReviewService(changes,client,mapper);
        for(String receipt:List.of("",",\"inputComplete\":false")) {
            when(client.reviewArtifacts(anyMap())).thenReturn(mapper.readTree("{\"passed\":true,\"findings\":[]"+receipt+"}"));
            assertThatThrownBy(()->service.review(1L,2L,proposal.getProposalId(),1,proposal.getResultHash()))
                .hasMessage("ARTIFACT_INPUT_INCOMPLETE");
        }
        when(client.reviewArtifacts(anyMap())).thenReturn(mapper.readTree("{\"passed\":true,\"inputComplete\":true,\"findings\":[]}"));
        assertThat(service.review(1L,2L,proposal.getProposalId(),1,proposal.getResultHash()).path("passed").asBoolean()).isTrue();
    }
}

package com.timiroom.domain.spec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.integration.dto.*;
import com.timiroom.domain.integration.entity.IntegrationGrant;
import com.timiroom.domain.integration.service.IntegrationAccessService;
import com.timiroom.domain.spec.service.*;
import com.timiroom.domain.spec.dto.*;
import com.timiroom.domain.graph.service.GraphCalculator;
import com.timiroom.domain.integrationjob.service.*;
import com.timiroom.domain.integrationjob.repository.IntegrationJobRepository;
import com.timiroom.domain.github.*;
import com.timiroom.domain.project.repository.ProjectMemberRepository;
import com.timiroom.infra.mcp.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType.*;

@ExtendWith(MockitoExtension.class)
class TimiroomMcpToolsTest {
    @Test void invalidIntermediateShaLengthNeverStartsAReview() {
        assertThatThrownBy(()->tools.invoke("timiroom_start_consistency_check",actor,Map.of("projectId",1,"snapshotId",snapshot.toString(),
            "repoId",1,"pullNumber",1,"expectedHeadSha","a".repeat(41),"idempotencyKey","key"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(tasks);
    }
    @Mock IntegrationAccessService access;
    @Mock DocumentAccessService documents;
    @Mock SpecSnapshotService snapshots;
    @Mock SpecChangeService changes;
    @Mock GraphCalculator graph;
    @Mock IntegrationTaskService tasks;
    @Mock IntegrationJobService jobs;
    @Mock IntegrationJobRepository jobRepository;
    @Mock ProjectRepoLinkRepository repoLinks;
    @Mock GithubRepoRepository repos;
    @Mock ProjectMemberRepository members;
    @Mock PullRequestConsistencyService pullRequests;
    @Spy ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    @Spy McpPaginationService pages=new McpPaginationService("test-only-signing-key-at-least-32-bytes",new ObjectMapper(),Clock.systemUTC());
    @InjectMocks TimiroomMcpTools tools;
    final UUID snapshot=UUID.randomUUID();
    final IntegrationPrincipal actor=new IntegrationPrincipal(2L,"client",UUID.randomUUID(),Set.of("specs:read"));
    @Test void exposesExactlyTenScopedToolsWithoutOriginalWriteOrApproval() {
        assertThat(tools.definitions().stream().map(d->d.name()).toList()).containsExactlyInAnyOrder(
            "timiroom_list_projects","timiroom_get_project_context","timiroom_get_spec_manifest","timiroom_read_spec",
            "timiroom_get_change_impact","timiroom_propose_spec_change","timiroom_get_spec_change","timiroom_start_artifact_review",
            "timiroom_start_consistency_check","timiroom_get_job");
        for(var definition:tools.definitions()) assertThat(definition.schema().additionalProperties()).isFalse();
    }
    @Test void rejectsCallerIdentityAndUnknownInputsBeforeReadingDocuments() {
        assertThatThrownBy(()->tools.invoke("timiroom_read_spec",actor,Map.of("projectId",1,"snapshotId",snapshot.toString(),"documentType","API_SPEC","memberId",99)))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(snapshots);
    }
    @Test void everyReadChecksGrantBeforeReassemblingExactDocument() {
        String content="{\"한국어\":\"😀\"}";
        when(snapshots.get(1L,2L,snapshot)).thenReturn(new SpecSnapshotDto(snapshot,1L,1,2L,Instant.now(),List.of(new SpecDocumentDto(API_SPEC,10L,5L,2,"hash",content))));
        var data=tools.invoke("timiroom_read_spec",actor,Map.of("projectId",1,"snapshotId",snapshot.toString(),"documentType","API_SPEC"));
        assertThat(data.path("content").asText()).isEqualTo(content);
        assertThat(data.path("truncated").asBoolean()).isFalse();
        verify(access).require(actor,1L,IntegrationScope.SPECS_READ);
    }
    @Test void revokedGrantStopsReadEvenWithKnownSnapshotId() {
        doThrow(new SecurityException("ACCESS_DENIED")).when(access).require(actor,1L,IntegrationScope.SPECS_READ);
        assertThatThrownBy(()->tools.invoke("timiroom_read_spec",actor,Map.of("projectId",1,"snapshotId",snapshot.toString(),"documentType","API_SPEC")))
            .isInstanceOf(SecurityException.class);
        verifyNoInteractions(snapshots);
    }
}

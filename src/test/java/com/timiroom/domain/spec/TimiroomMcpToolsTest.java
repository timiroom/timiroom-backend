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
    @Test void specReadCannotReadNewBoundReviewDetailWithoutConsistencyRead() throws Exception {
        var proposal=UUID.randomUUID();
        var base=new SpecSnapshotDto(snapshot,1L,1,2L,Instant.now(),List.of());
        var view=new SpecChangeProposalDto(proposal,1L,snapshot,1,
            com.timiroom.domain.spec.entity.SpecChangeProposal.State.READY,base,mapper.createObjectNode(),
            mapper.createArrayNode(),mapper.createObjectNode(),"test","hash",null,false,
            mapper.createObjectNode().put("summary","restricted finding"),false);
        when(changes.view(1L,2L,proposal)).thenReturn(view);
        var page=tools.invoke("timiroom_get_spec_change",actor,Map.of("projectId",1,"proposalId",proposal.toString()));
        assertThat(mapper.readTree(page.path("content").asText()).has("artifactReview")).isFalse();
        assertThat(mapper.readTree(page.path("content").asText()).has("artifactReviewPassed")).isFalse();
    }
    @Test void specChangeLeadsWithSummaryAndOmitsDocumentBodiesUnlessRequested() throws Exception {
        var proposal=UUID.randomUUID();
        var doc=new SpecDocumentDto(API_SPEC,5L,6L,1,"h","{\"endpoints\":[\"ORIGINAL_BODY\"]}");
        var base=new SpecSnapshotDto(snapshot,1L,8,2L,Instant.now(),List.of(doc));
        var documents=mapper.createObjectNode();documents.set("API_SPEC",mapper.createObjectNode().put("changed","CHANGED_BODY"));
        var diffs=mapper.createArrayNode();diffs.addObject().put("type","API_SPEC").put("path","/endpoints/0");
        var view=new SpecChangeProposalDto(proposal,1L,snapshot,1,com.timiroom.domain.spec.entity.SpecChangeProposal.State.READY,base,documents,
            diffs,mapper.createObjectNode(),"test","hash",null,false,null,false);
        when(changes.view(1L,2L,proposal)).thenReturn(view);
        var summary=mapper.readTree(tools.invoke("timiroom_get_spec_change",actor,Map.of("projectId",1,"proposalId",proposal.toString())).path("content").asText());
        assertThat(summary.fieldNames().next()).isEqualTo("proposalId");
        assertThat(summary.path("changedDocumentTypes").get(0).asText()).isEqualTo("API_SPEC");
        assertThat(summary.path("diffs").size()).isEqualTo(1);
        assertThat(summary.toString()).doesNotContain("ORIGINAL_BODY").doesNotContain("CHANGED_BODY");
        var full=mapper.readTree(tools.invoke("timiroom_get_spec_change",actor,Map.of("projectId",1,"proposalId",proposal.toString(),"documentType","API_SPEC")).path("content").asText());
        assertThat(full.toString()).contains("ORIGINAL_BODY").contains("CHANGED_BODY");
    }
    @Test void runOnlyIdempotentSubmissionNeverReturnsStoredReviewResults() {
        var principal=new IntegrationPrincipal(2L,"client",UUID.randomUUID(),Set.of("consistency:run"));
        var proposal=UUID.randomUUID();
        var stored=new com.timiroom.domain.integrationjob.dto.JobDto(UUID.randomUUID(),
            com.timiroom.domain.integrationjob.entity.IntegrationJob.Kind.ARTIFACT_REVIEW,
            com.timiroom.domain.integrationjob.entity.IntegrationJob.State.COMPLETED,"binding",1,
            mapper.createObjectNode().put("privateFinding","restricted result"),null);
        when(tasks.beginArtifactReview(1L,2L,proposal,1,"same-key")).thenReturn(stored);
        var result=tools.invoke("timiroom_start_artifact_review",principal,Map.of("projectId",1,
            "proposalId",proposal.toString(),"proposalRevision",1,"idempotencyKey","same-key"));
        assertThat(result.has("result")).isFalse();
        assertThat(result.has("error")).isFalse();
        assertThat(result.path("jobId").asText()).isEqualTo(stored.jobId().toString());
        assertThat(result.path("status").asText()).isEqualTo("COMPLETED");
    }
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
    @Test void exposesElevenScopedToolsIncludingExplicitPmApproval() {
        assertThat(tools.definitions().stream().map(d->d.name()).toList()).containsExactlyInAnyOrder(
            "timiroom_list_projects","timiroom_get_project_context","timiroom_get_spec_manifest","timiroom_read_spec",
            "timiroom_get_change_impact","timiroom_propose_spec_change","timiroom_get_spec_change","timiroom_start_artifact_review",
            "timiroom_start_consistency_check","timiroom_get_job","timiroom_approve_spec_change");
        for(var definition:tools.definitions()) assertThat(definition.schema().additionalProperties()).isFalse();
    }
    Map<String,Object> approvalInput(UUID proposal) {
        return Map.of("projectId",1,"proposalId",proposal.toString(),"proposalRevision",1,"expectedSnapshotId",snapshot.toString());
    }
    SpecChangeProposalDto approvalView(UUID proposal, com.timiroom.domain.spec.entity.SpecChangeProposal.State state, UUID published) {
        return new SpecChangeProposalDto(proposal,1L,snapshot,1,state,
            new SpecSnapshotDto(snapshot,1L,8,2L,Instant.now(),List.of()),mapper.createObjectNode(),
            mapper.createArrayNode(),mapper.createObjectNode(),"test","hash",published,true,null,false);
    }
    @Test void approvalRequiresSeparateScopeBeforeReadingOrWritingProposal() {
        var scope=IntegrationScope.from("specs:approve");
        doThrow(new SecurityException("INSUFFICIENT_SCOPE")).when(access).require(actor,1L,scope);
        assertThatThrownBy(()->tools.invoke("timiroom_approve_spec_change",actor,approvalInput(UUID.randomUUID())))
            .hasMessage("INSUFFICIENT_SCOPE");
        verifyNoInteractions(changes,snapshots);
    }
    @Test void approvalPublishesThroughExistingPmServiceAndReturnsFixedSnapshotMetadata() {
        var proposal=UUID.randomUUID();var published=UUID.randomUUID();
        var view=approvalView(proposal,com.timiroom.domain.spec.entity.SpecChangeProposal.State.READY,null);
        when(changes.view(1L,2L,proposal)).thenReturn(view);
        when(changes.approve(1L,2L,proposal,1,false)).thenReturn(new SpecSnapshotDto(published,1L,9,2L,Instant.now(),List.of()));
        var result=tools.invoke("timiroom_approve_spec_change",actor,approvalInput(proposal));
        assertThat(result.path("snapshotId").asText()).isEqualTo(published.toString());
        assertThat(result.path("revision").asInt()).isEqualTo(9);
        assertThat(result.path("alreadyApproved").asBoolean()).isFalse();
        verify(access).require(actor,1L,IntegrationScope.from("specs:approve"));
        verify(changes).approve(1L,2L,proposal,1,false);
    }
    @Test void approvalRejectsWrongBaselineAndRevisionWithoutPublishing() {
        var proposal=UUID.randomUUID();
        var view=approvalView(proposal,com.timiroom.domain.spec.entity.SpecChangeProposal.State.READY,null);
        when(changes.view(1L,2L,proposal)).thenReturn(view);
        var wrongBase=new HashMap<>(approvalInput(proposal));wrongBase.put("expectedSnapshotId",UUID.randomUUID().toString());
        var wrongRevision=new HashMap<>(approvalInput(proposal));wrongRevision.put("proposalRevision",2);
        for(var input:List.of(wrongBase,wrongRevision))
            assertThatThrownBy(()->tools.invoke("timiroom_approve_spec_change",actor,input)).hasMessageStartingWith("SPEC_CONFLICT");
        verify(changes,never()).approve(anyLong(),anyLong(),any(),anyInt(),anyBoolean());
    }
    @Test void approvalReplayReturnsOriginalPublicationWithoutPublishingAgainAndRechecksPm() {
        var proposal=UUID.randomUUID();var published=UUID.randomUUID();
        var view=approvalView(proposal,com.timiroom.domain.spec.entity.SpecChangeProposal.State.APPROVED,published);
        when(changes.view(1L,2L,proposal)).thenReturn(view);
        when(snapshots.get(1L,2L,published)).thenReturn(new SpecSnapshotDto(published,1L,9,2L,Instant.now(),List.of()));
        var result=tools.invoke("timiroom_approve_spec_change",actor,approvalInput(proposal));
        assertThat(result.path("alreadyApproved").asBoolean()).isTrue();
        assertThat(result.path("revision").asInt()).isEqualTo(9);
        verify(documents).requirePm(1L,2L);
        verify(changes,never()).approve(anyLong(),anyLong(),any(),anyInt(),anyBoolean());
    }
    @Test void approvalDoesNotAcknowledgeWarningsImplicitlyAndPropagatesExistingGuards() {
        var proposal=UUID.randomUUID();
        var view=approvalView(proposal,com.timiroom.domain.spec.entity.SpecChangeProposal.State.READY,null);
        when(changes.view(1L,2L,proposal)).thenReturn(view);
        when(changes.approve(1L,2L,proposal,1,false)).thenThrow(new IllegalStateException("ARTIFACT_REVIEW_WARNINGS"));
        assertThatThrownBy(()->tools.invoke("timiroom_approve_spec_change",actor,approvalInput(proposal))).hasMessage("ARTIFACT_REVIEW_WARNINGS");
        var explicit=new HashMap<>(approvalInput(proposal));explicit.put("acknowledgeWarnings",true);
        when(changes.approve(1L,2L,proposal,1,true)).thenReturn(new SpecSnapshotDto(UUID.randomUUID(),1L,9,2L,Instant.now(),List.of()));
        tools.invoke("timiroom_approve_spec_change",actor,explicit);
        verify(changes).approve(1L,2L,proposal,1,true);
        var invalid=new HashMap<>(explicit);invalid.put("acknowledgeWarnings","true");
        assertThatThrownBy(()->tools.invoke("timiroom_approve_spec_change",actor,invalid)).hasMessage("INVALID_INPUT");
    }
    @Test void nonPmCannotApproveEvenWithApprovalScope() {
        doThrow(new SecurityException("ACCESS_DENIED")).when(documents).requirePm(1L,2L);
        assertThatThrownBy(()->tools.invoke("timiroom_approve_spec_change",actor,approvalInput(UUID.randomUUID()))).hasMessage("ACCESS_DENIED");
        verifyNoInteractions(changes,snapshots);
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

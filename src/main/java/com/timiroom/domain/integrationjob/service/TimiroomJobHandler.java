package com.timiroom.domain.integrationjob.service;
import com.fasterxml.jackson.databind.*;
import com.timiroom.domain.integrationjob.entity.IntegrationJob;
import com.timiroom.domain.spec.dto.*;
import com.timiroom.domain.spec.entity.SpecChangeProposal;
import com.timiroom.domain.spec.service.*;
import com.timiroom.domain.github.PullRequestConsistencyService;
import com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

@Service @RequiredArgsConstructor
public class TimiroomJobHandler implements IntegrationJobHandler {
    private final SpecChangeService proposals;
    private final DocumentEditService editor;
    private final DocumentImpactService impact;
    private final ArtifactReviewService reviews;
    private final SpecSnapshotService snapshots;
    private final PullRequestConsistencyService pullRequests;
    private final ObjectMapper mapper;
    @Override public boolean supports(IntegrationJob.Kind kind) { return true; }
    @Override public boolean requiresProvider(IntegrationJob job) {
        if(job.getKind()!=IntegrationJob.Kind.SPEC_CHANGE) return true;
        try {
            var id=UUID.fromString(mapper.readTree(job.getRequestJson()).path("proposalId").asText());
            return proposals.get(job.getProjectId(),job.getActorId(),id).getState()!=SpecChangeProposal.State.READY;
        } catch(java.io.IOException e) {throw new IllegalStateException("INVALID_JOB_PAYLOAD");}
    }
    @Override public void onFailure(IntegrationJob job) {
        if(job.getKind()!=IntegrationJob.Kind.SPEC_CHANGE) return;
        try {
            var id=UUID.fromString(mapper.readTree(job.getRequestJson()).path("proposalId").asText());
            proposals.fail(job.getProjectId(),id);
        } catch(IllegalStateException alreadyCompleted) { /* A recovered job may already have made the proposal READY. */ }
        catch(java.io.IOException invalidPayload) { throw new IllegalStateException("INVALID_JOB_PAYLOAD",invalidPayload); }
    }
    @Override public JsonNode execute(IntegrationJob job) {
        try {
            var input=mapper.readTree(job.getRequestJson());
            return switch(job.getKind()) {
                case SPEC_CHANGE -> generate(job,UUID.fromString(input.path("proposalId").asText()));
                case ARTIFACT_REVIEW -> reviews.review(job.getProjectId(),job.getActorId(),UUID.fromString(input.path("proposalId").asText()),
                    input.path("proposalRevision").asInt(),input.path("resultHash").asText());
                case PR_REVIEW -> {
                    var snapshot=snapshots.get(job.getProjectId(),job.getActorId(),UUID.fromString(input.path("snapshotId").asText()));
                    var result=pullRequests.analyzeSnapshot(job.getActorId(),snapshot,input.path("repoId").asLong(),
                        input.path("pullNumber").asInt(),input.path("expectedHeadSha").asText());
                    if(!result.baseSha().equals(input.path("expectedBaseSha").asText())) throw new IllegalStateException("PR_CHANGED");
                    yield mapper.valueToTree(result);
                }
            };
        } catch(java.io.IOException e) { throw new IllegalStateException("INVALID_JOB_PAYLOAD",e); }
    }
    private JsonNode generate(IntegrationJob job,UUID proposalId) throws java.io.IOException {
        var proposal=proposals.get(job.getProjectId(),job.getActorId(),proposalId);
        if(proposal.getState()==SpecChangeProposal.State.READY) return proposalResult(proposal);
        if(proposal.getState()!=SpecChangeProposal.State.GENERATING) throw new IllegalStateException("SPEC_CONFLICT");
        var base=mapper.readValue(proposal.getBaseJson(),SpecSnapshotDto.class);
        var instructionJson=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(proposal.getInputJson());
        var manual=instructionJson.remove("manualDocuments");
        var instruction=mapper.treeToValue(instructionJson,ChangeInstruction.class);
        var bundle=DocumentBundle.from(base);
        DocumentEditResult edited;
        if(manual!=null) {
            var changes=new ArrayList<DocumentEditResult.Change>();
            manual.fields().forEachRemaining(e->changes.add(new DocumentEditResult.Change(ArtifactType.valueOf(e.getKey()),e.getValue(),mapper.createArrayNode(),"사용자 직접 수정")));
            edited=new DocumentEditResult(changes,"TIMIROOM_MANUAL_V1");
        } else edited=editor.propose(bundle,instruction.targets(),instruction.instruction(),instruction.constraints());
        var contents=new EnumMap<ArtifactType,String>(ArtifactType.class);
        for(var change:edited.changes()) contents.put(change.type(),mapper.writeValueAsString(change.document()));
        var after=new DocumentBundle(bundle.projectId(),bundle.snapshotId(),bundle.documents().stream().map(doc ->
            new SpecDocumentDto(doc.type(),doc.artifactId(),doc.executionId(),doc.version(),doc.hash(),contents.getOrDefault(doc.type(),doc.content()))).toList());
        // Linked propagation stays inside the explicitly requested scope. A broader edit needs a new proposal.
        var allowed=EnumSet.copyOf(instruction.targets());
        var analyzed=impact.analyze(bundle,after,allowed);
        proposals.complete(job.getProjectId(),job.getActorId(),proposalId,edited,analyzed);
        return proposalResult(proposals.get(job.getProjectId(),job.getActorId(),proposalId));
    }
    private JsonNode proposalResult(SpecChangeProposal proposal) {
        return mapper.createObjectNode().put("proposalId",proposal.getProposalId().toString())
            .put("proposalRevision",proposal.getProposalRevision()).put("status",proposal.getState().name());
    }
}

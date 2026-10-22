package com.timiroom.domain.spec.service;
import com.fasterxml.jackson.databind.*;
import com.timiroom.domain.spec.entity.SpecChangeProposal;
import com.timiroom.infra.consistency.ConsistencyServiceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

@Service @RequiredArgsConstructor
public class ArtifactReviewService {
    private final SpecChangeService proposals;
    private final ConsistencyServiceClient client;
    private final ObjectMapper mapper;
    public JsonNode review(Long projectId,Long actorId,UUID proposalId,int revision,String expectedHash) {
        var proposal=proposals.get(projectId,actorId,proposalId);
        if(proposal.getState()!=SpecChangeProposal.State.READY || proposal.getProposalRevision()!=revision
                || !Objects.equals(expectedHash,proposal.getResultHash())) throw new IllegalStateException("SPEC_CONFLICT");
        var documents=mapper.createObjectNode();
        // 이번 변경안이 고친 문서. 검토 Agent는 이 문서와 관련된 문제만 경고로 올리고,
        // 바뀌지 않은 문서끼리의 기존 문제는 참고(INFO)로 낮춘다.
        var focus=new java.util.ArrayList<String>();
        try {
            for(var doc:proposals.readBase(proposal).documents()) documents.set(doc.type().name(),mapper.readTree(doc.content()));
            var changed=mapper.readTree(proposal.getDocumentsJson());
            changed.fields().forEachRemaining(entry->{ documents.set(entry.getKey(),entry.getValue()); focus.add(entry.getKey()); });
        } catch(java.io.IOException e) { throw new IllegalStateException("Invalid proposal document",e); }
        var response=client.reviewArtifacts(Map.of("projectId",projectId,"artifacts",documents,"focus",focus));
        if(!response.path("inputComplete").isBoolean() || !response.path("inputComplete").asBoolean())
            throw new IllegalStateException("ARTIFACT_INPUT_INCOMPLETE");
        if(!response.isObject() || !response.path("passed").isBoolean() || !response.path("findings").isArray())
            throw new IllegalStateException("INVALID_ARTIFACT_REVIEW");
        var result=response.deepCopy();
        boolean passed=response.path("passed").asBoolean();
        for(var finding:response.get("findings")) if(!Set.of("PASS","INFO").contains(finding.path("severity").asText())) passed=false;
        ((com.fasterxml.jackson.databind.node.ObjectNode)result).put("passed",passed).put("kind","ARTIFACT_REVIEW");
        // Recheck authorization and revision after the provider returned.
        var current=proposals.get(projectId,actorId,proposalId);
        if(current.getState()!=SpecChangeProposal.State.READY || current.getProposalRevision()!=revision
                || !Objects.equals(current.getResultHash(),expectedHash)) throw new IllegalStateException("SPEC_CONFLICT");
        return result;
    }
}

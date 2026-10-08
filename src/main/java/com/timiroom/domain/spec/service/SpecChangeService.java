package com.timiroom.domain.spec.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.timiroom.domain.spec.dto.*;
import com.timiroom.domain.spec.entity.SpecChangeProposal;
import com.timiroom.domain.spec.repository.SpecChangeProposalRepository;
import com.timiroom.domain.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @RequiredArgsConstructor
public class SpecChangeService {
    private final SpecChangeProposalRepository proposals;
    private final SpecSnapshotService snapshots;
    private final ArtifactWriteService writer;
    private final DocumentAccessService access;
    private final ProjectRepository projects;
    private final ArtifactReviewResultReader reviews;
    private final ObjectMapper mapper;
    @Transactional
    public SpecChangeProposal createManual(Long projectId,Long actorId,UUID snapshotId,ChangeInstruction input,
            Map<com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType,JsonNode> documents,
            Map<com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType,String> expectedHashes) {
        var base=snapshots.get(projectId,actorId,snapshotId);
        if(!snapshots.latest(projectId,actorId).snapshotId().equals(snapshotId)) throw new IllegalStateException("SPEC_CONFLICT");
        if(documents==null || documents.isEmpty() || documents.size()>4 || expectedHashes==null
            || !expectedHashes.keySet().equals(documents.keySet()) || !input.targets().containsAll(documents.keySet()))
            throw new IllegalArgumentException("INVALID_SPEC_CHANGE");
        var manual=mapper.createObjectNode();
        for(var target:input.targets()) {
            DocumentEditService.docType(target);access.requireWrite(projectId,actorId,target);
            if(base.documents().stream().noneMatch(d->d.type()==target)) throw new IllegalArgumentException("INVALID_SPEC_CHANGE");
        }
        for(var entry:documents.entrySet()) {
            var original=base.documents().stream().filter(d->d.type()==entry.getKey()).findFirst().orElseThrow();
            if(!Objects.equals(original.hash(),expectedHashes.get(entry.getKey()))) throw new IllegalStateException("SPEC_CONFLICT");
            if(!DocumentShape.valid(entry.getKey(),entry.getValue()) || json(entry.getValue()).length()>1000000)
                throw new IllegalArgumentException("INVALID_SPEC_CHANGE");
            manual.set(entry.getKey().name(),entry.getValue().deepCopy());
        }
        var request=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(input);request.set("manualDocuments",manual);
        return proposals.save(new SpecChangeProposal(projectId,actorId,snapshotId,json(base),json(request),input.instruction()));
    }
    @Transactional
    public SpecChangeProposal create(Long projectId, Long actorId, UUID snapshotId, ChangeInstruction input) {
        var base=snapshots.get(projectId, actorId, snapshotId);
        if(!snapshots.latest(projectId,actorId).snapshotId().equals(snapshotId)) throw new IllegalStateException("SPEC_CONFLICT");
        for(var target:input.targets()) {
            DocumentEditService.docType(target); access.requireWrite(projectId, actorId, target);
            if(base.documents().stream().noneMatch(d->d.type()==target)) throw new IllegalArgumentException("기준 문서에 대상이 없습니다");
        }
        return proposals.save(new SpecChangeProposal(projectId, actorId, snapshotId, json(base), json(input), input.instruction()));
    }
    @Transactional(readOnly=true)
    public SpecChangeProposal get(Long projectId, Long actorId, UUID proposalId) {
        access.requireRead(projectId,actorId);
        return proposals.findByProposalIdAndProjectId(proposalId,projectId).orElseThrow(()->new IllegalArgumentException("PROPOSAL_NOT_FOUND"));
    }
    @Transactional(readOnly=true)
    public SpecChangeProposalDto view(Long projectId,Long actorId,UUID proposalId) {
        var proposal=get(projectId,actorId,proposalId);
        try { return new SpecChangeProposalDto(proposal.getProposalId(),projectId,proposal.getSnapshotId(),proposal.getProposalRevision(),
            proposal.getState(),readBase(proposal),tree(proposal.getDocumentsJson()),tree(proposal.getDiffsJson()),tree(proposal.getImpactJson()),
            proposal.getExecutor(),proposal.getResultHash(),proposal.getApprovedSnapshotId(),
            proposal.getResultHash()!=null && reviews.hasPass(proposalId,proposal.getProposalRevision(),proposal.getResultHash())); }
        catch(JsonProcessingException e) {throw new IllegalStateException("Invalid stored proposal",e);}
    }
    private JsonNode tree(String value) throws JsonProcessingException {return value==null?mapper.nullNode():mapper.readTree(value);}
    /** Called after the worker has computed results outside a transaction. */
    @Transactional
    public void complete(Long projectId, Long actorId, UUID proposalId, DocumentEditResult edit, DocumentImpactResult impact) {
        access.requireRead(projectId, actorId);
        var proposal=locked(projectId,proposalId);
        if(!Objects.equals(proposal.getActorId(),actorId)) throw new SecurityException("ACCESS_DENIED");
        var base=readBase(proposal);
        if(!Objects.equals(impact.snapshotId(),base.snapshotId())) throw new IllegalStateException("SPEC_CONFLICT");
        var proposed=mapper.createObjectNode();
        for(var change:edit.changes()) putDocument(base,proposed,change.type(),change.document(),projectId,actorId);
        for(var update:impact.updates()) putDocument(base,proposed,update.type(),update.document(),projectId,actorId);
        if(proposed.isEmpty()) throw new IllegalStateException("NO_SPEC_CHANGES");
        proposal.ready(json(proposed),json(edit.changes()),json(impact),edit.executor()+"+"+impact.executor());
    }
    private void putDocument(SpecSnapshotDto base, com.fasterxml.jackson.databind.node.ObjectNode proposed,
            com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType type, JsonNode document, Long project, Long actor) {
        if(proposed.has(type.name()) || !DocumentShape.valid(type,document) || base.documents().stream().noneMatch(d->d.type()==type))
            throw new IllegalStateException("INVALID_SPEC_CHANGE");
        access.requireWrite(project,actor,type); proposed.set(type.name(),document.deepCopy());
    }
    @Transactional
    public void fail(Long projectId, UUID proposalId) { locked(projectId,proposalId).fail(); }
    @Transactional
    public SpecSnapshotDto approve(Long projectId, Long actorId, UUID proposalId, int revision) {
        access.requirePm(projectId, actorId);
        // Same lock order as publication prevents project/artifact lock inversion.
        projects.findForUpdate(projectId).orElseThrow(()->new SecurityException("ACCESS_DENIED"));
        var proposal=locked(projectId,proposalId);
        if(proposal.getState()!=SpecChangeProposal.State.READY || proposal.getProposalRevision()!=revision)
            throw new IllegalStateException("SPEC_CONFLICT");
        if(!snapshots.latest(projectId,actorId).snapshotId().equals(proposal.getSnapshotId()))
            throw new IllegalStateException("SPEC_CONFLICT: published baseline changed");
        if(!reviews.hasPass(proposalId,revision,proposal.getResultHash())) throw new IllegalStateException("ARTIFACT_REVIEW_REQUIRED");
        var base=readBase(proposal);
        JsonNode changed;
        try { changed=mapper.readTree(proposal.getDocumentsJson()); }
        catch(JsonProcessingException e) { throw new IllegalStateException("Invalid stored proposal",e); }
        var commands=new ArrayList<ArtifactWriteCommand>();
        for(var doc:base.documents()) {
            String content=changed.has(doc.type().name()) ? json(changed.get(doc.type().name())) : doc.content();
            commands.add(new ArtifactWriteCommand(doc.artifactId(),doc.version(),doc.hash(),content));
        }
        writer.writeBatch(projectId,actorId,commands);
        var published=snapshots.publish(projectId,actorId);
        var expectedIds=base.documents().stream().map(SpecDocumentDto::artifactId).collect(java.util.stream.Collectors.toSet());
        var publishedIds=published.documents().stream().map(SpecDocumentDto::artifactId).collect(java.util.stream.Collectors.toSet());
        if(!expectedIds.equals(publishedIds)) throw new IllegalStateException("SPEC_CONFLICT: latest execution changed");
        proposal.approve(published.snapshotId());
        return published;
    }
    @Transactional
    public void reject(Long projectId, Long actorId, UUID proposalId, int revision) {
        access.requirePm(projectId,actorId);
        var proposal=locked(projectId,proposalId);
        if(proposal.getProposalRevision()!=revision) throw new IllegalStateException("SPEC_CONFLICT");
        proposal.reject();
    }
    private SpecChangeProposal locked(Long projectId,UUID proposalId) {
        return proposals.findForUpdate(proposalId,projectId).orElseThrow(()->new IllegalArgumentException("PROPOSAL_NOT_FOUND"));
    }
    SpecSnapshotDto readBase(SpecChangeProposal p) {
        try { return mapper.readValue(p.getBaseJson(),SpecSnapshotDto.class); }
        catch(JsonProcessingException e) { throw new IllegalStateException("Invalid stored snapshot",e); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch(JsonProcessingException e) { throw new IllegalStateException("Serialization failed",e); }
    }
}

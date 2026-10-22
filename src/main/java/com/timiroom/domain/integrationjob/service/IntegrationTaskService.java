package com.timiroom.domain.integrationjob.service;
import com.fasterxml.jackson.databind.*;
import com.timiroom.domain.integrationjob.dto.JobDto;
import com.timiroom.domain.integrationjob.entity.IntegrationJob;
import com.timiroom.domain.project.repository.ProjectRepository;
import com.timiroom.domain.spec.dto.*;
import com.timiroom.domain.spec.entity.SpecChangeProposal;
import com.timiroom.domain.spec.service.*;
import com.timiroom.domain.github.PullRequestConsistencyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @RequiredArgsConstructor
public class IntegrationTaskService {
    private final IntegrationJobService jobs;
    private final SpecChangeService proposals;
    private final SpecSnapshotService snapshots;
    private final ProjectRepository projects;
    private final PullRequestConsistencyService pullRequests;
    private final DocumentAccessService access;
    private final ObjectMapper mapper;
    @Transactional
    public JobDto beginManualChange(Long project,Long actor,UUID snapshot,ChangeInstruction instruction,
            Map<com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType,JsonNode> documents,
            Map<com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType,String> hashes,String key) {
        access.requireRead(project,actor);projects.findForUpdate(project).orElseThrow(()->new SecurityException("ACCESS_DENIED"));
        var request=mapper.createObjectNode().put("snapshotId",snapshot.toString());
        request.set("changeInstruction",mapper.valueToTree(instruction));request.set("manualDocuments",mapper.valueToTree(documents));
        request.set("expectedHashes",mapper.valueToTree(hashes));var binding=snapshot+":SPEC_CHANGE_MANUAL_V1";
        var previous=jobs.lookup(project,actor,IntegrationJob.Kind.SPEC_CHANGE,key,request,binding);
        if(previous.isPresent()) return previous.get();
        var proposal=proposals.createManual(project,actor,snapshot,instruction,documents,hashes);
        request.put("proposalId",proposal.getProposalId().toString());return jobs.submit(project,actor,IntegrationJob.Kind.SPEC_CHANGE,key,request,binding);
    }
    @Transactional
    public JobDto beginChange(Long project,Long actor,UUID snapshot,ChangeInstruction instruction,String key) {
        access.requireRead(project,actor);
        projects.findForUpdate(project).orElseThrow(()->new SecurityException("ACCESS_DENIED"));
        var request=mapper.createObjectNode().put("snapshotId",snapshot.toString());
        request.set("changeInstruction",mapper.valueToTree(instruction));
        var binding=snapshot+":SPEC_CHANGE_V1";
        var previous=jobs.lookup(project,actor,IntegrationJob.Kind.SPEC_CHANGE,key,request,binding);
        if(previous.isPresent()) return previous.get();
        var proposal=proposals.create(project,actor,snapshot,instruction);
        request.put("proposalId",proposal.getProposalId().toString());
        return jobs.submit(project,actor,IntegrationJob.Kind.SPEC_CHANGE,key,request,binding);
    }
    @Transactional
    public JobDto beginArtifactReview(Long project,Long actor,UUID id,int revision,String key) {
        var proposal=proposals.get(project,actor,id);
        if(proposal.getProposalRevision()!=revision || proposal.getResultHash()==null) throw new IllegalStateException("SPEC_CONFLICT");
        var request=mapper.createObjectNode().put("proposalId",id.toString()).put("proposalRevision",revision).put("resultHash",proposal.getResultHash());
        var binding=id+":"+revision+":"+proposal.getResultHash();
        var previous=jobs.lookup(project,actor,IntegrationJob.Kind.ARTIFACT_REVIEW,key,request,binding);
        if(previous.isPresent()) return previous.get();
        if(proposal.getState()!=SpecChangeProposal.State.READY) throw new IllegalStateException("SPEC_CONFLICT");
        return jobs.submit(project,actor,IntegrationJob.Kind.ARTIFACT_REVIEW,key,request,binding);
    }
    public JobDto beginPullRequestReview(Long project,Long actor,UUID snapshot,Long repo,int pull,String head,String key) {
        snapshots.get(project,actor,snapshot);
        var pinned=pullRequests.pinPullRequest(project,actor,repo,pull,head);
        var request=mapper.createObjectNode().put("snapshotId",snapshot.toString()).put("repoId",repo).put("pullNumber",pull)
            .put("expectedHeadSha",pinned.headSha()).put("expectedBaseSha",pinned.baseSha()).put("evaluatorVersion",pullRequests.evaluatorVersion());
        var binding=DocumentHash.of(request.toString());
        return jobs.submit(project,actor,IntegrationJob.Kind.PR_REVIEW,key,request,binding);
    }
}

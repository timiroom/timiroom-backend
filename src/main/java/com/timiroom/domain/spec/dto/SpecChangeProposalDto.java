package com.timiroom.domain.spec.dto;
import com.fasterxml.jackson.databind.JsonNode;
import com.timiroom.domain.spec.entity.SpecChangeProposal;
import java.util.UUID;
public record SpecChangeProposalDto(UUID proposalId,Long projectId,UUID snapshotId,int proposalRevision,
    SpecChangeProposal.State status,SpecSnapshotDto base,JsonNode documents,JsonNode diffs,JsonNode impact,
    String executor,String resultHash,UUID approvedSnapshotId,boolean artifactReviewPassed,JsonNode artifactReview) {}

package com.timiroom.domain.spec.dto;
import com.timiroom.domain.spec.entity.SpecChangeProposal;
import java.time.Instant;
import java.util.UUID;
public record SpecChangeSummary(UUID proposalId,UUID snapshotId,int proposalRevision,
    SpecChangeProposal.State status,String instruction,Instant createdAt) {}

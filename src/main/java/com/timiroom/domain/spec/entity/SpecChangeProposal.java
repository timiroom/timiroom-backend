package com.timiroom.domain.spec.entity;

import com.timiroom.domain.spec.service.DocumentHash;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="spec_change_proposal")
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class SpecChangeProposal {
    public enum State { GENERATING, READY, FAILED, APPROVED, REJECTED, STALE }
    @Id private UUID proposalId;
    @Column(nullable=false) private Long projectId;
    @Column(nullable=false) private Long actorId;
    @Column(nullable=false) private UUID snapshotId;
    @Column(nullable=false, columnDefinition="TEXT") private String baseJson;
    @Column(nullable=false, columnDefinition="TEXT") private String inputJson;
    @Column(nullable=false, columnDefinition="TEXT") private String instruction;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private State state;
    @Column(nullable=false) private int proposalRevision;
    @Column(columnDefinition="TEXT") private String documentsJson;
    @Column(columnDefinition="TEXT") private String diffsJson;
    @Column(columnDefinition="TEXT") private String impactJson;
    private String executor;
    private String resultHash;
    private UUID approvedSnapshotId;
    @Column(nullable=false) private Instant createdAt;
    public SpecChangeProposal(Long projectId, Long actorId, UUID snapshotId, String baseJson, String inputJson, String instruction) {
        this.proposalId=UUID.randomUUID(); this.projectId=projectId; this.actorId=actorId; this.snapshotId=snapshotId;
        this.baseJson=baseJson; this.inputJson=inputJson; this.instruction=instruction;
        state=State.GENERATING; proposalRevision=1; createdAt=Instant.now();
    }
    public void ready(String documents, String diffs, String impact, String executor) {
        require(State.GENERATING);
        documentsJson=documents; diffsJson=diffs; impactJson=impact; this.executor=executor;
        resultHash=DocumentHash.of(baseJson+"\n"+proposalRevision+"\n"+documents); state=State.READY;
    }
    public void fail() { require(State.GENERATING); state=State.FAILED; }
    public void approve(UUID snapshotId) { require(State.READY); approvedSnapshotId=snapshotId; state=State.APPROVED; }
    public void reject() { require(State.READY); state=State.REJECTED; }
    public void stale() { require(State.READY); state=State.STALE; }
    private void require(State expected) { if(state!=expected) throw new IllegalStateException("SPEC_CONFLICT"); }
}

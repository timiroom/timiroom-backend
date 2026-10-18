package com.timiroom.domain.spec.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/**
 * 명세 발행 지문과 블록체인 기록 상태. 칼럼은 V20261022_01과 하나도 빠짐없이 맞춘다(운영은 ddl-auto: update).
 * 체인 관련 칸은 전송 단계에서 채운다.
 */
@Entity @Table(name="spec_anchor",uniqueConstraints={
    @UniqueConstraint(columnNames={"snapshot_id"}),@UniqueConstraint(columnNames={"project_id","revision"})})
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class SpecAnchor {
    public enum State { PENDING, SENDING, SUBMITTED, CONFIRMED, FAILED, UNKNOWN }
    @Id @Column(name="anchor_id",nullable=false) private UUID anchorId;
    @Column(name="snapshot_id",nullable=false) private UUID snapshotId;
    @Column(name="project_id",nullable=false) private Long projectId;
    @Column(nullable=false) private int revision;
    @Column(name="manifest_json",nullable=false,columnDefinition="TEXT") private String manifestJson;
    @Column(nullable=false,length=64) private String digest;
    @Column(name="prev_digest",length=64) private String prevDigest;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) private State state;
    @Column(nullable=false) private boolean backfilled;
    @Column(name="chain_id") private Long chainId;
    @Column(name="contract_address",length=42) private String contractAddress;
    private Long nonce;
    @Column(name="tx_hash",length=66) private String txHash;
    @Column(name="raw_tx",columnDefinition="TEXT") private String rawTx;
    @Column(name="block_number") private Long blockNumber;
    @Column(nullable=false) private int attempts;
    @Column(name="lease_id") private UUID leaseId;
    @Column(name="lease_until") private Instant leaseUntil;
    @Column(name="next_attempt_at",nullable=false) private Instant nextAttemptAt;
    @Column(name="error_code",length=64) private String errorCode;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="submitted_at") private Instant submittedAt;
    @Column(name="confirmed_at") private Instant confirmedAt;

    public SpecAnchor(UUID snapshotId,Long projectId,int revision,String manifestJson,String digest,String prevDigest,boolean backfilled,Instant now) {
        this.anchorId=UUID.randomUUID();this.snapshotId=snapshotId;this.projectId=projectId;this.revision=revision;
        this.manifestJson=manifestJson;this.digest=digest;this.prevDigest=prevDigest;this.backfilled=backfilled;
        this.state=State.PENDING;this.nextAttemptAt=now;this.createdAt=now;
    }
}

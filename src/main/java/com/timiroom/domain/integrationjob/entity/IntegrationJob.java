package com.timiroom.domain.integrationjob.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.*;

@Entity @Table(name="integration_job",uniqueConstraints=@UniqueConstraint(columnNames={"project_id","actor_id","kind","idempotency_key"}))
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class IntegrationJob {
    public enum Kind { SPEC_CHANGE, ARTIFACT_REVIEW, PR_REVIEW }
    public enum State { QUEUED, RUNNING, COMPLETED, FAILED }
    @Id private UUID jobId;
    @Column(nullable=false) private Long projectId;
    @Column(nullable=false) private Long actorId;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private Kind kind;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private State state;
    @Column(nullable=false,length=128) private String idempotencyKey;
    @Column(nullable=false,length=64) private String requestHash;
    @Column(nullable=false,columnDefinition="TEXT") private String requestJson;
    @Column(nullable=false,length=255) private String bindingKey;
    @Column(columnDefinition="TEXT") private String resultJson;
    private String errorCode;
    @Column(nullable=false) private int attempts;
    private UUID leaseId;
    private Instant leaseUntil;
    @Column(nullable=false) private Instant createdAt;
    private Instant completedAt;
    private Instant providerStartedAt;
    public IntegrationJob(Long project,Long actor,Kind kind,String key,String hash,String request,String binding,Instant now) {
        jobId=UUID.randomUUID(); projectId=project;actorId=actor;this.kind=kind;idempotencyKey=key;
        requestHash=hash;requestJson=request;bindingKey=binding;createdAt=now;state=State.QUEUED;
    }
    public void claim(UUID lease,Instant now) {
        if(state!=State.QUEUED || attempts>=2) throw new IllegalStateException("JOB_CONFLICT");
        attempts++;leaseId=lease;leaseUntil=now.plusSeconds(300);state=State.RUNNING;
    }
    public void complete(UUID lease,String result,Instant now) {
        requireLease(lease,now);resultJson=result;completedAt=now;state=State.COMPLETED;leaseUntil=null;
    }
    public void fail(UUID lease,String error,Instant now) {
        requireLease(lease,now);errorCode=error;completedAt=now;state=State.FAILED;leaseUntil=null;
    }
    public void recover(Instant now) {
        if(state!=State.RUNNING || leaseUntil.isAfter(now)) return;
        leaseId=null;leaseUntil=null;
        if(providerStartedAt!=null || attempts>=2) { state=State.FAILED;errorCode=providerStartedAt!=null?"PROVIDER_OUTCOME_UNKNOWN":"JOB_TIMEOUT";completedAt=now; }
        else state=State.QUEUED;
    }
    public void providerStarted(UUID lease,Instant now) { requireLease(lease,now);providerStartedAt=now; }
    private void requireLease(UUID lease,Instant now) {
        if(state!=State.RUNNING || !Objects.equals(leaseId,lease) || !leaseUntil.isAfter(now))
            throw new IllegalStateException("JOB_LEASE_EXPIRED");
    }
}

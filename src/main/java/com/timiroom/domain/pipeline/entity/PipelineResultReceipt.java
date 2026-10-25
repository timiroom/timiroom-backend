package com.timiroom.domain.pipeline.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

@Entity
@Table(name = "pipeline_result_receipt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PipelineResultReceipt {
    @Id
    @Column(name = "pipeline_id", length = 36)
    private String pipelineId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private boolean completed;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_id", referencedColumnName = "pipeline_id", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private PipelineExecution execution;

    public PipelineResultReceipt(String pipelineId, String payload) {
        this.pipelineId = pipelineId;
        this.payload = payload;
    }

    public void updatePayload(String payload) { this.payload = payload; }
    public void complete() { this.completed = true; }
}

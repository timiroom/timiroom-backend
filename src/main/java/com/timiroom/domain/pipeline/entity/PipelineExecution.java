package com.timiroom.domain.pipeline.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "pipeline_execution")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class PipelineExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "execution_id")
    private Long executionId;

    @Column(name = "pipeline_id", length = 36, nullable = false, unique = true)
    private String pipelineId;

    @Column(name = "requirement_id")
    private Long requirementId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "user_query", columnDefinition = "TEXT")
    private String userQuery;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ExecutionStatus status = ExecutionStatus.RUNNING;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void complete(int retryCount) {
        this.status = ExecutionStatus.COMPLETED;
        this.retryCount = retryCount;
        this.completedAt = LocalDateTime.now();
    }

    public void fail(String errorMessage) {
        this.status = ExecutionStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = LocalDateTime.now();
    }

    public enum ExecutionStatus {
        RUNNING, COMPLETED, FAILED, RETRYING
    }
}

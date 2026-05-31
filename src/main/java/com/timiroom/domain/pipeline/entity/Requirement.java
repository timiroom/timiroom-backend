package com.timiroom.domain.pipeline.entity;

import com.timiroom.domain.pipeline.enums.RequirementStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "requirement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Requirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "requirement_id")
    private Long requirementId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(length = 300, nullable = false)
    private String title;

    /** FormData 전체 JSON 저장 — 파이프라인 재실행 가능 */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private RequirementStatus status = RequirementStatus.DRAFT;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void updateStatus(RequirementStatus status) {
        this.status = status;
    }
}

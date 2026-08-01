package com.timiroom.domain.pipeline.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "pipeline_artifact")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class PipelineArtifact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "artifact_id")
    private Long artifactId;

    @Column(name = "execution_id", nullable = false)
    private Long executionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "artifact_type", nullable = false, length = 30)
    private ArtifactType artifactType;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(nullable = false)
    @Builder.Default
    private int version = 1;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public void updateContent(String content) {
        this.content = content;
    }

    public enum ArtifactType {
        PRD, DB_SCHEMA, API_SPEC, FEATURE_LIST, MARKET_RESEARCH, QA_REPORT
    }
}

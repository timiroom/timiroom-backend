package com.rag.pipeline.project;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "projects")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "active";

    @Column(name = "prd_document", columnDefinition = "TEXT")
    private String prdDocument;

    @Column(name = "db_schema", columnDefinition = "TEXT")
    private String dbSchema;

    @Column(name = "api_spec", columnDefinition = "TEXT")
    private String apiSpec;

    @Column(name = "feature_list", columnDefinition = "TEXT")
    private String featureList;

    @Column(name = "market_research", columnDefinition = "TEXT")
    private String marketResearch;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

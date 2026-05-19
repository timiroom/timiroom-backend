package com.timiroom.domain.pipeline.entity;

import com.timiroom.domain.pipeline.enums.ArtifactType;
import com.timiroom.global.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "pipeline_artifact")
public class PipelineArtifact extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id")
    private PipelineExecution execution;

    @Enumerated(EnumType.STRING)
    @Column(name = "artifact_type")
    private ArtifactType type;

    @Lob
    @Column(name = "content")
    private String content;

    @Version
    @Column(name = "version")
    private Integer version;
}

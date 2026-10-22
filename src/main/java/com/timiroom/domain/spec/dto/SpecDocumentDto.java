package com.timiroom.domain.spec.dto;
import com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType;
public record SpecDocumentDto(ArtifactType type,Long artifactId,Long executionId,int version,String hash,String content) {}

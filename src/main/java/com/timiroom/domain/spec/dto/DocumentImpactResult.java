package com.timiroom.domain.spec.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.timiroom.domain.graph.dto.GraphResponse;
import com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType;
import java.util.List;
import java.util.UUID;

public record DocumentImpactResult(UUID snapshotId, GraphResponse graph, String summary,
        List<Update> updates, String executor) {
    public DocumentImpactResult { updates = List.copyOf(updates); }
    public record Update(ArtifactType type, String reason, JsonNode document) {}
}

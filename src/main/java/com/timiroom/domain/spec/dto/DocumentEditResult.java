package com.timiroom.domain.spec.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType;
import java.util.List;

public record DocumentEditResult(List<Change> changes, String executor) {
    public DocumentEditResult { changes = List.copyOf(changes); }
    public record Change(ArtifactType type, JsonNode document, JsonNode sectionDiffs, String reason) {}
}

package com.rag.pipeline.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record ChatResponse(
    String message,
    @JsonProperty("isComplete") boolean isComplete,
    int step,
    JsonNode formData,
    List<String> suggestions
) {}

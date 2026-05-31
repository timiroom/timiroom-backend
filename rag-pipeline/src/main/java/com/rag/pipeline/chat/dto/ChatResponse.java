package com.rag.pipeline.chat.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record ChatResponse(
    String message,
    boolean isComplete,
    JsonNode formData,
    List<String> suggestions
) {}

package com.rag.pipeline.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class ProjectResponse {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String id;
    private String name;
    private String description;
    private String status;
    private JsonNode prdDocument;
    private JsonNode dbSchema;
    private JsonNode apiSpec;
    private List<String> featureList;
    private String createdAt;
    private String updatedAt;

    public static ProjectResponse from(Project p) {
        return ProjectResponse.builder()
            .id(p.getId().toString())
            .name(p.getName())
            .description(p.getDescription() != null ? p.getDescription() : "")
            .status(p.getStatus())
            .prdDocument(parseJson(p.getPrdDocument()))
            .dbSchema(parseJson(p.getDbSchema()))
            .apiSpec(parseJson(p.getApiSpec()))
            .featureList(parseList(p.getFeatureList()))
            .createdAt(p.getCreatedAt() != null ? p.getCreatedAt().toString() : "")
            .updatedAt(p.getUpdatedAt() != null ? p.getUpdatedAt().toString() : "")
            .build();
    }

    private static JsonNode parseJson(String json) {
        try {
            if (json == null || json.isBlank()) return MAPPER.createObjectNode();
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseList(String json) {
        try {
            if (json == null || json.isBlank()) return List.of();
            return MAPPER.readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}

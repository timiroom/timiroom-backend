package com.rag.pipeline.phase2.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.pipeline.phase2.state.PipelineState;
import lombok.Builder;
import lombok.Getter;
import java.util.List;

@Getter
@Builder
public class GenerateResponse {
    private String projectId;
    private String projectName;
    private String pipelineId;
    private String query;
    private List<String> featureList;
    private JsonNode prdDocument;
    private JsonNode dbSchema;
    private JsonNode apiSpec;
    private String status;
    private int retryCount;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static GenerateResponse from(PipelineState state) {
        return GenerateResponse.builder()
            .featureList(state.getFeatureList())
            .prdDocument(parseJson(state.getPrdDocument()))
            .dbSchema(parseJson(state.getDbSchema()))
            .apiSpec(parseJson(state.getApiSpec()))
            .status(state.getStatusMessage())
            .retryCount(state.getRetryCount())
            .build();
    }

    public static GenerateResponse from(PipelineState state, String projectId, String projectName) {
        return GenerateResponse.builder()
            .projectId(projectId)
            .projectName(projectName)
            .featureList(state.getFeatureList())
            .prdDocument(parseJson(state.getPrdDocument()))
            .dbSchema(parseJson(state.getDbSchema()))
            .apiSpec(parseJson(state.getApiSpec()))
            .status(state.getStatusMessage())
            .retryCount(state.getRetryCount())
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
}
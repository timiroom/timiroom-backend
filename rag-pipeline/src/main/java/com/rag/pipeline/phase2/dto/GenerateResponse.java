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
    private String marketResearch;
    private String status;
    private int retryCount;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static GenerateResponse from(PipelineState state) {
        return GenerateResponse.builder()
            .featureList(state.getFeatureList())
            .prdDocument(parseJson(state.getPrdDocument()))
            .dbSchema(parseJson(state.getDbSchema()))
            .apiSpec(parseJson(state.getApiSpec()))
            .marketResearch(state.getMarketResearch())
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
            .marketResearch(state.getMarketResearch())
            .status(state.getStatusMessage())
            .retryCount(state.getRetryCount())
            .build();
    }

    private static JsonNode parseJson(String json) {
        if (json == null || json.isBlank() || json.equals("{}") || json.equals("[]")) return null;
        try {
            JsonNode node = MAPPER.readTree(json);
            // 파싱 성공해도 빈 오브젝트/배열이면 null 반환 (saveArtifact 스킵 방지 + 프론트 빈 상태 처리)
            if (node.isObject() && node.isEmpty()) return null;
            if (node.isArray() && node.isEmpty()) return null;
            return node;
        } catch (Exception e) {
            return null;
        }
    }
}
package com.rag.pipeline.phase2.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Getter;
import java.util.List;

@Getter
@Builder
public class GenerateResponse {
    private String pipelineId;
    private String query;
    private List<String> featureList;
    private JsonNode prdDocument;
    private JsonNode dbSchema;
    private JsonNode apiSpec;
    private String status;
    private int retryCount;
}
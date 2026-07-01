package com.rag.pipeline.phase1.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PersonaRecommendationService {

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${spring.ai.openai.api-key}")
    private String foundryApiKey;

    private static final String FOUNDRY_URL =
            "https://align-it-resource.services.ai.azure.com/openai/v1/responses";

    public PersonaRecommendationResponse recommend(PersonaRecommendationRequest req) {
        try {
            var pd = req.problemDefinition();
            String userContent = String.format("""
                    프로젝트명: %s
                    설명: %s
                    핵심 문제: %s
                    현재 해결 방식: %s
                    이상적인 상태: %s

                    위 서비스를 가장 필요로 할 타겟 유저 2명의 페르소나를 추천해주세요.
                    실제로 존재할 법한 구체적인 사람으로 작성해주세요.

                    {"personas":[{"persona":"","usageEnvironment":"","biggestPainPoint":""}]}
                    """,
                    req.projectName(),
                    req.projectDescription(),
                    pd.currentPainPoint(),
                    pd.currentSolution(),
                    pd.idealState());

            Map<String, Object> payload = Map.of(
                    "model", "gpt-5.4-mini",
                    "instructions", "당신은 UX 리서처입니다. 서비스 정보를 보고 가장 핵심적인 타겟 유저 페르소나를 추천하세요. 반드시 JSON만 반환하고 코드 블록이나 다른 텍스트는 포함하지 마세요.",
                    "input", List.of(Map.of("role", "user", "content", userContent)),
                    "max_output_tokens", 512,
                    "temperature", 0.4
            );

            String raw = restClientBuilder.build()
                    .post()
                    .uri(FOUNDRY_URL)
                    .header("Authorization", "Bearer " + foundryApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            String text = extractText(objectMapper.readTree(raw));
            return objectMapper.readValue(text.trim(), PersonaRecommendationResponse.class);

        } catch (Exception e) {
            log.warn("페르소나 추천 실패: {}", e.getMessage());
            return PersonaRecommendationResponse.empty();
        }
    }

    private String extractText(JsonNode root) {
        for (JsonNode item : root.path("output")) {
            if ("message".equals(item.path("type").asText())) {
                for (JsonNode block : item.path("content")) {
                    if ("output_text".equals(block.path("type").asText())) {
                        return block.path("text").asText();
                    }
                }
            }
        }
        return "{\"personas\":[]}";
    }
}

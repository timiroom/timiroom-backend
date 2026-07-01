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
public class TechStackRecommendationService {

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${spring.ai.openai.api-key}")
    private String foundryApiKey;

    private static final String FOUNDRY_URL =
            "https://align-it-resource.services.ai.azure.com/openai/v1/responses";

    public TechStackResponse recommend(String projectName, String desc, String platform) {
        try {
            String userContent = String.format("""
                    프로젝트명: %s
                    설명: %s
                    플랫폼: %s

                    아래 JSON 형식으로 파트별 기술 스택을 추천해주세요.
                    각 파트에 2~3개를 추천하세요.
                    플랫폼이 WEB이면 mobile은 빈 배열로 반환하세요.
                    플랫폼이 APP이면 frontend는 빈 배열로 반환하세요.

                    {"frontend":[],"backend":[],"database":[],"devops":[],"mobile":[]}
                    """, projectName, desc, platform);

            Map<String, Object> payload = Map.of(
                    "model", "gpt-5.4-mini",
                    "instructions", "당신은 소프트웨어 아키텍처 전문가입니다. 반드시 JSON만 반환하고 코드 블록이나 다른 텍스트는 포함하지 마세요.",
                    "input", List.of(Map.of("role", "user", "content", userContent)),
                    "max_output_tokens", 512,
                    "temperature", 0.3
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
            return objectMapper.readValue(text.trim(), TechStackResponse.class);

        } catch (Exception e) {
            log.warn("기술 스택 추천 실패, 기본값 반환: {}", e.getMessage());
            return TechStackResponse.defaultFor(platform);
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
        return "{}";
    }
}

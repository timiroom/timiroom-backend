package com.rag.pipeline.phase1.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * STEP 2 — Query Expansion
 *
 * 사용자의 모호한 자연어 요청을 검색에 최적화된
 * 기술 검색어 여러 개로 확장합니다.
 *
 * 예시:
 *   입력: "로그인 만들어줘"
 *   출력: ["로그인 만들어줘",        ← 원본 항상 포함
 *          "사용자 인증",
 *          "JWT 토큰 인증",
 *          "Spring Security 설정",
 *          "로그인 API 엔드포인트",
 *          "세션 관리"]
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryExpansionService {

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${spring.ai.openai.api-key}")
    private String foundryApiKey;

    private static final String FOUNDRY_URL =
            "https://align-it-resource.services.ai.azure.com/openai/v1/responses";

    private static final String SYSTEM_INSTRUCTIONS = """
            당신은 소프트웨어 개발 전문가입니다.
            사용자의 요청을 분석하여 RAG 시스템 검색에 최적화된 기술 검색어를 생성하세요.

            규칙:
            - 정확히 5개의 검색어를 생성하세요
            - 각 검색어는 줄바꿈으로 구분하세요
            - 검색어만 출력하고 번호, 설명 등 부가 텍스트는 절대 포함하지 마세요
            - 기술적이고 구체적인 용어를 사용하세요
            """;

    /**
     * 사용자 쿼리를 5개의 기술 검색어로 확장
     * 원본 쿼리 포함 총 6개 반환
     */
    public List<String> expand(String userQuery) {
        log.debug("Query Expansion 시작 — 원본: '{}'", userQuery);

        Map<String, Object> payload = Map.of(
                "model", "gpt-5.4-mini",
                "instructions", SYSTEM_INSTRUCTIONS,
                "input", List.of(Map.of("role", "user", "content", "사용자 요청: " + userQuery)),
                "max_output_tokens", 500,
                "temperature", 0.2
        );

        try {
            String raw = restClientBuilder.build()
                    .post()
                    .uri(FOUNDRY_URL)
                    .header("Authorization", "Bearer " + foundryApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            String response = extractText(objectMapper.readTree(raw));

            List<String> expanded = Arrays.stream(response.split("\\n"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .limit(5)
                    .collect(Collectors.toList());

            expanded.add(0, userQuery);

            log.debug("Query Expansion 완료 — {} queries: {}", expanded.size(), expanded);
            return expanded;

        } catch (Exception e) {
            log.warn("Query Expansion 실패 — fallback 적용: {}", e.getMessage());
            return List.of(userQuery);
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
        return "";
    }
}

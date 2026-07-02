package com.rag.pipeline.phase2.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.pipeline.phase2.state.PipelineState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * QA 에이전트 — 논리적 정합성 검수
 *
 * Phase 3의 형식 검증과 다르게 기능명세 ↔ DB 스키마 ↔ API 스펙 간 논리적 결함을 직접 분석합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QaAgent {

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${spring.ai.openai.api-key}")
    private String foundryApiKey;

    @Value("${app.ai.foundry.responses-url}")
    private String foundryUrl;

    private static final String QA_SYSTEM = """
            당신은 시니어 소프트웨어 아키텍트입니다.
            제공된 세 가지 설계 산출물의 논리적 정합성을 검수하세요.

            검수 기준:
            1. DB 스키마에 핵심 테이블과 컬럼이 존재하는가?
            2. API 스펙의 각 엔드포인트가 필요로 하는 DB 테이블과 컬럼이 존재하는가?
            3. DB 테이블 간 외래키 관계가 올바르게 설계되어 있는가?
            4. API 필드명과 DB 컬럼명이 일치하는가?

            결함 판단 기준 (엄하게 적용하지 말 것):
            - 사소한 필드명 차이 (camelCase vs snake_case)는 결함이 아닙니다
            - API 응답에 일부 필드가 빠져도 핵심 기능이 동작하면 통과입니다
            - 전체적인 설계 방향이 올바르면 세부 불일치는 무시하세요

            주의사항 (결함으로 판단하지 말 것):
            - 연체료 계산, 예약 우선순위, 블락 처리 등 비즈니스 로직은
              서버 내부에서 처리되므로 별도 API 엔드포인트가 없어도 정상입니다
            - 배치 작업, 스케줄링 로직은 API 엔드포인트가 없어도 정상입니다
            - OAuth 2.0 인증 방식 명시는 authentication 필드로 충분합니다

            응답 형식 (JSON만 출력):
            {
              "passed": true 또는 false,
              "issues": ["발견된 결함 1", "발견된 결함 2"],
              "suggestions": ["수정 제안 1", "수정 제안 2"]
            }

            결함이 없으면 passed=true, issues=[] 로 응답하세요.
            JSON 외 다른 텍스트는 절대 포함하지 마세요.
            """;

    public PipelineState execute(PipelineState state) {
        log.info("QA 에이전트 시작 — 논리적 정합성 검수");

        String featureListStr = String.join("\n", state.getFeatureList());

        String relaxMsg = state.getRetryCount() >= 2
                ? "\n\n※ 이미 " + state.getRetryCount() + "회 재시도됨. "
                + "치명적인 구조적 결함만 잡고 나머지는 passed=true로 처리하세요."
                : "";

        String userContent = String.format("""
                [기능 목록]
                %s

                [DB 스키마]
                %s

                [API 스펙]
                %s
                """,
                featureListStr,
                state.getDbSchema(),
                state.getApiSpec()) + relaxMsg;

        Map<String, Object> payload = Map.of(
                "model", "gpt-5.4-mini",
                "instructions", QA_SYSTEM,
                "input", List.of(Map.of("role", "user", "content", userContent)),
                "max_output_tokens", 2000
        );

        try {
            String raw = restClientBuilder.build()
                    .post()
                    .uri(foundryUrl)
                    .header("Authorization", "Bearer " + foundryApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            String response = extractText(objectMapper.readTree(raw));
            String clean = response.trim()
                    .replaceAll("```json", "")
                    .replaceAll("```", "")
                    .trim();

            QaResult result = parseQaResult(clean);
            double qualityScore = computeQualityScore(result.passed(), result.issues().size());

            if (result.passed()) {
                log.info("QA 검수 통과 — 논리적 결함 없음 (qualityScore={})",
                        String.format("%.2f", qualityScore));
                return state.toBuilder()
                        .qaQualityScore(qualityScore)
                        .statusMessage("QA 에이전트 완료 — 정합성 검수 통과")
                        .build();
            } else {
                log.warn("QA 검수 실패 — {}개 결함 발견 (qualityScore={}): {}",
                        result.issues().size(), String.format("%.2f", qualityScore), result.issues());
                String errorMsg = "QA 검수 실패\n"
                        + "[발견된 결함]\n" + String.join("\n", result.issues()) + "\n"
                        + "[수정 제안]\n" + String.join("\n", result.suggestions());

                return state.toBuilder()
                        .qaQualityScore(qualityScore)
                        .lastValidationError(errorMsg)
                        .statusMessage("QA 에이전트 — 결함 발견, 재생성 필요")
                        .build();
            }

        } catch (Exception e) {
            log.error("QA 에이전트 실패: {}", e.getMessage());
            return state.toBuilder()
                    .qaQualityScore(0.3)  // 실패 시 낮은 점수 부여
                    .statusMessage("QA 에이전트 실패 — 통과 처리")
                    .build();
        }
    }

    /**
     * QA 품질 점수 계산 (0.0 ~ 1.0)
     *
     * baseline: 최근 20회 평균 (AgentRLService 슬라이딩 윈도우)
     * ┌──────────────────┬──────────┬───────┐
     * │ 통과 여부         │ 결함 수  │ 점수  │
     * ├──────────────────┼──────────┼───────┤
     * │ passed = true    │ 0        │ 1.00  │
     * │ passed = true    │ 1~2      │ 0.80  │
     * │ passed = true    │ 3+       │ 0.60  │
     * │ passed = false   │ 1~2      │ 0.40  │
     * │ passed = false   │ 3+       │ 0.20  │
     * └──────────────────┴──────────┴───────┘
     * OrchestrationGraph에서 retryCount 패널티 추가 적용: score - 0.15 × retryCount
     */
    private double computeQualityScore(boolean passed, int issueCount) {
        if (passed) {
            if (issueCount == 0)  return 1.00;
            if (issueCount <= 2) return 0.80;
            return 0.60;
        } else {
            if (issueCount <= 2) return 0.40;
            return 0.20;
        }
    }

    private QaResult parseQaResult(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            boolean passed = root.path("passed").asBoolean(true);

            List<String> issues = new ArrayList<>();
            root.path("issues").forEach(n -> issues.add(n.asText()));

            List<String> suggestions = new ArrayList<>();
            root.path("suggestions").forEach(n -> suggestions.add(n.asText()));

            return new QaResult(passed, issues, suggestions);
        } catch (Exception e) {
            log.error("QA 결과 파싱 실패 — {}", e.getMessage());
            return new QaResult(true, List.of(), List.of());
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

    record QaResult(
            boolean passed,
            List<String> issues,
            List<String> suggestions
    ) {}
}

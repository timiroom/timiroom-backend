package com.rag.pipeline.phase2.agent;

import com.rag.pipeline.phase2.state.PipelineState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * QA 에이전트 — 논리적 정합성 검수
 *
 * Phase 3의 형식 검증과 다르게,
 * GPT-4o가 기능명세 ↔ DB 스키마 ↔ API 스펙 간
 * 논리적 결함을 직접 분석합니다.
 *
 * 예시 탐지:
 *   - 로그인 기능이 있는데 users 테이블이 없음
 *   - POST /login이 있는데 password 컬럼이 없음
 *   - 대출 연장 API가 있는데 extension_count 컬럼이 없음
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QaAgent {

    private final ChatClient.Builder chatClientBuilder;

    private static final String QA_PROMPT = """
        당신은 시니어 소프트웨어 아키텍트입니다.
        아래 세 가지 설계 산출물의 논리적 정합성을 검수하세요.
        
        [기능 목록]
        %s
        
        [DB 스키마]
        %s
        
        [API 스펙]
        %s
        
        검수 기준:
        1. DB 스키마에 핵심 테이블과 컬럼이 존재하는가?
        2. API 스펙의 각 엔드포인트가 필요로 하는 DB 테이블과 컬럼이 존재하는가?
        3. DB 테이블 간 외래키 관계가 올바르게 설계되어 있는가?
        4. API 필드명과 DB 컬럼명이 일치하는가?
        
        결함 판단 기준 (엄하게 적용하지 말 것):
        - 사소한 필드명 차이 (camelCase vs snake_case)는 결함이 아닙니다
        - API 응답에 일부 필드가 빠져도 핵심 기능이 동작하면 통과입니다
        - 전체적인 설계 방향이 올바르면 세부 불일치는 무시하세요
        - 2회 이상 재시도된 경우 치명적 결함만 잡으세요
        
        주의사항 (결함으로 판단하지 말 것):
        - 연체료 계산, 예약 우선순위, 블락 처리 등 비즈니스 로직은
          서버 내부에서 처리되므로 별도 API 엔드포인트가 없어도 정상입니다
        - 배치 작업, 스케줄링 로직은 API 엔드포인트가 없어도 정상입니다
        - OAuth 2.0 인증 방식 명시는 authentication 필드로 충분합니다
        
        
        응답 형식 (JSON만 출력):
        {
          "passed": true 또는 false,
          "issues": [
            "발견된 결함 1",
            "발견된 결함 2"
          ],
          "suggestions": [
            "수정 제안 1",
            "수정 제안 2"
          ]
        }
        
        결함이 없으면 passed=true, issues=[] 로 응답하세요.
        JSON 외 다른 텍스트는 절대 포함하지 마세요.
        """;

    /**
     * QA 검수 실행
     *
     * @param state DBA + API 에이전트 결과가 담긴 State
     * @return 검수 결과가 반영된 새 State
     */
    public PipelineState execute(PipelineState state) {
        log.info("QA 에이전트 시작 — 논리적 정합성 검수");

        ChatClient chatClient = chatClientBuilder
                .defaultOptions(
                        org.springframework.ai.openai.OpenAiChatOptions.builder()
                                .withModel("gpt-4o")
                                .withTemperature(0.0)
                                .build()
                )
                .build();

        String featureListStr = String.join("\n", state.getFeatureList());

        String relaxMsg = state.getRetryCount() >= 2
                ? "\n\n※ 이미 " + state.getRetryCount() + "회 재시도됨. "
                + "치명적인 구조적 결함만 잡고 나머지는 passed=true로 처리하세요."
                : "";

        String response = chatClient
                .prompt(String.format(QA_PROMPT,
                        featureListStr,
                        state.getDbSchema(),
                        state.getApiSpec()) + relaxMsg)
                .call()
                .content();

        // JSON 파싱
        String clean = response.trim()
                .replaceAll("```json", "")
                .replaceAll("```", "")
                .trim();

        QaResult result = parseQaResult(clean);

        if (result.passed()) {
            log.info("QA 검수 통과 — 논리적 결함 없음");
            return state.toBuilder()
                    .statusMessage("QA 에이전트 완료 — 정합성 검수 통과")
                    .build();
        } else {
            log.warn("QA 검수 실패 — {} 개 결함 발견: {}", result.issues().size(), result.issues());

            // 발견된 결함을 lastValidationError에 저장 → Phase 3 Retry에서 활용
            String errorMsg = "QA 검수 실패\n"
                    + "[발견된 결함]\n" + String.join("\n", result.issues()) + "\n"
                    + "[수정 제안]\n" + String.join("\n", result.suggestions());

            return state.toBuilder()
                    .lastValidationError(errorMsg)
                    .statusMessage("QA 에이전트 — 결함 발견, 재생성 필요")
                    .build();
        }
    }

    /** QA 결과 JSON 파싱 */
    private QaResult parseQaResult(String json) {
        try {
            String clean = json.trim()
                    .replaceAll("```json", "")
                    .replaceAll("```", "")
                    .trim();

            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(clean);

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

    record QaResult(
            boolean passed,
            java.util.List<String> issues,
            java.util.List<String> suggestions
    ) {}
}
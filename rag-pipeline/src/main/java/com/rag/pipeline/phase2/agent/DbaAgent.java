package com.rag.pipeline.phase2.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag.pipeline.phase2.rl.AgentRLService;
import com.rag.pipeline.phase2.state.PipelineState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * DBA 에이전트 — DB 스키마 설계
 *
 * PM 에이전트의 dbaInstruction을 받아 테이블 구조, 관계, 인덱스를 포함한 DB 스키마를 생성.
 * featureList 전체 매핑 검증 후 누락 시 prdFeedbackFromDba에 피드백 저장.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbaAgent {

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final AgentRLService agentRLService;

    @Value("${spring.ai.openai.api-key}")
    private String foundryApiKey;

    private static final String FOUNDRY_URL =
            "https://align-it-resource.services.ai.azure.com/openai/v1/responses";

    private static final String DBA_SYSTEM = """
            당신은 시니어 DBA입니다.
            아래 지시사항을 바탕으로 DB 스키마를 JSON 형식으로만 설계하세요.

            응답 형식:
            {
              "tables": [
                {
                  "name": "테이블명",
                  "columns": [
                    {"name": "컬럼명", "type": "타입", "constraints": "제약조건"}
                  ],
                  "indexes": ["인덱스 설명"]
                }
              ],
              "relationships": ["관계 설명"],
              "prdIssues": "PRD에서 누락되거나 불명확한 기능 (없으면 빈 문자열)"
            }

            규칙:
            - JSON 외 다른 텍스트는 절대 포함하지 마세요
            - 각 테이블에 반드시 id(PK), created_at, updated_at 컬럼을 포함하세요
            - 외래키 관계를 명확히 표현하세요
            - 기능 목록의 모든 기능이 테이블에 반영되어야 합니다
            - API에서 필요로 하는 모든 테이블과 컬럼을 반드시 포함하세요
            - relationships 배열에 반드시 카디널리티를 명시하세요
              형식: "테이블A (1:N) 테이블B" 또는 "테이블A (1:1) 테이블B" 또는 "테이블A (N:M) 테이블B"
            - 통계/요약 테이블도 데이터를 집계하는 원본 테이블과 relationships에 명시하세요

            ⚠️ 설계 후 반드시 아래 체크리스트를 검증하세요:
            - [ ] featureList의 모든 기능이 최소 1개 이상의 테이블과 매핑되는가?
            - [ ] 각 테이블에 PK, created_at, updated_at이 있는가?
            - [ ] 외래키 관계가 relationships에 모두 명시되어 있는가?

            누락된 기능이 있으면 prdIssues 필드에 명시하세요.
            모두 충족되면 prdIssues는 빈 문자열로 작성하세요.
            """;

    private static final String DBA_USER_TEMPLATE = """
            지시사항:
            %s

            기능 목록:
            %s

            컨텍스트 (이전 결함 수정 지시 포함):
            %s
            """;

    // ── Collaborative API ───────────────────────────────────────

    /**
     * Round 1: PM 지시 없이 Phase1 컨텍스트를 직접 해석하여 DB 스키마 초안 작성.
     * 모든 에이전트와 동시 실행되므로 PM featureList 없이 진행.
     */
    public PipelineState executeDraft(PipelineState state) {
        log.info("DBA 에이전트 초안 작성 시작 — Phase1 컨텍스트 직접 해석");

        String context = state.getContextPrompt() != null ? state.getContextPrompt() : state.getUserQuery();
        String marketSnippet = state.getMarketResearch() != null
                ? state.getMarketResearch().substring(0, Math.min(1500, state.getMarketResearch().length()))
                : "없음";

        String userContent = """
                요구사항:
                %s

                시장 데이터 (상위 요약):
                %s

                위 요구사항을 분석하여 서비스에 필요한 모든 DB 스키마를 설계하세요.
                PM 별도 지시 없이 요구사항에서 직접 필요한 기능과 데이터 구조를 도출하세요.
                """.formatted(context, marketSnippet);

        double temperature = agentRLService.getTemperature("dba");
        agentRLService.logAgentRun(state.getSessionId(), "dba", temperature);

        Map<String, Object> payload = Map.of(
                "model", "gpt-5.4-mini",
                "instructions", DBA_SYSTEM,
                "input", List.of(Map.of("role", "user", "content", userContent)),
                "max_output_tokens", 8000,
                "temperature", temperature
        );

        String clean = "{}";
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
            String candidate = response.trim().replaceAll("```json", "").replaceAll("```", "").trim();

            JsonNode root = objectMapper.readTree(candidate);
            ((com.fasterxml.jackson.databind.node.ObjectNode) root).remove("prdIssues");
            clean = objectMapper.writeValueAsString(root);

        } catch (Exception e) {
            log.error("DBA 초안 작성 실패 — 빈 스키마로 폴백: {}", e.getMessage());
        }

        log.info("DBA 에이전트 초안 완료");
        return state.toBuilder()
                .dbSchema(clean)
                .prdFeedbackFromDba("")
                .build();
    }

    /**
     * Round 2: PM featureList + PRD 초안 + API 스펙 초안을 참조하여 스키마 보완 및 불일치 수정.
     * RL temperature는 executeDraft와 동일한 값(현재 최적값)을 사용하되 재로그 없이.
     */
    public PipelineState refine(PipelineState state) {
        log.info("DBA 에이전트 피드백 반영 수정 시작");

        String featureStr = (state.getFeatureList() != null && !state.getFeatureList().isEmpty())
                ? "- " + String.join("\n- ", state.getFeatureList())
                : "(PM 기능 목록 없음)";

        String prdSnippet = state.getPrdDocument() != null
                ? state.getPrdDocument().substring(0, Math.min(2000, state.getPrdDocument().length()))
                : "(PRD 초안 없음)";

        // QA retry 시 contextPrompt에 결함 목록이 담겨 있으면 반드시 포함
        String qaFeedbackSection = "";
        if (state.getContextPrompt() != null && state.getContextPrompt().contains("QA 지적 결함")) {
            int idx = state.getContextPrompt().indexOf("=== QA 지적 결함");
            qaFeedbackSection = "\n\n" + state.getContextPrompt().substring(idx);
        }

        String userContent = """
                [당신의 초안 DB 스키마]
                %s

                [PM 기능 목록 — 모든 기능이 스키마에 반영되어야 함]
                %s

                [PRD 요구사항 (주요 부분)]
                %s

                [API 스펙 초안 — API 엔드포인트에서 필요한 테이블/컬럼 확인]
                %s
                %s
                위 결과물들과 당신의 초안을 비교하여 수정된 최종 DB 스키마를 작성하세요.
                수정 기준:
                1. QA 지적 결함이 있으면 최우선으로 수정 (누락 테이블/컬럼 추가)
                2. API 엔드포인트에서 필요한 테이블/컬럼이 없으면 추가
                3. PM 기능 목록에서 스키마에 반영되지 않은 기능이 있으면 추가
                4. PRD 요구사항에 명시된 데이터 구조가 없으면 추가
                5. API 스펙과 불일치하는 컬럼명/타입이 있으면 수정
                """.formatted(
                        state.getDbSchema() != null ? state.getDbSchema() : "{}",
                        featureStr,
                        prdSnippet,
                        state.getApiSpec() != null
                                ? state.getApiSpec().substring(0, Math.min(3000, state.getApiSpec().length()))
                                : "(API 초안 없음)",
                        qaFeedbackSection
        );

        double temperature = agentRLService.getCurrentTemperature("dba");

        Map<String, Object> payload = Map.of(
                "model", "gpt-5.4-mini",
                "instructions", DBA_SYSTEM,
                "input", List.of(Map.of("role", "user", "content", userContent)),
                "max_output_tokens", 8000,
                "temperature", temperature
        );

        String clean = state.getDbSchema() != null ? state.getDbSchema() : "{}";
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
            String candidate = response.trim().replaceAll("```json", "").replaceAll("```", "").trim();

            JsonNode root = objectMapper.readTree(candidate);
            ((com.fasterxml.jackson.databind.node.ObjectNode) root).remove("prdIssues");
            clean = objectMapper.writeValueAsString(root);

        } catch (Exception e) {
            log.error("DBA 수정 실패 — 이전 스키마 유지: {}", e.getMessage());
        }

        log.info("DBA 에이전트 수정 완료");
        return state.toBuilder()
                .dbSchema(clean)
                .prdFeedbackFromDba("")
                .build();
    }

    // ── 기존 Sequential API ──────────────────────────────────────

    public PipelineState execute(PipelineState state) {
        log.info("DBA 에이전트 시작");

        String featureListStr = "- " + String.join("\n- ", state.getFeatureList());
        String context = state.getContextPrompt() != null ? state.getContextPrompt() : "";
        String userContent = String.format(DBA_USER_TEMPLATE,
                state.getDbaInstruction(), featureListStr, context);

        // RL: epsilon-greedy로 temperature 선택 후 기록
        double temperature = agentRLService.getTemperature("dba");
        agentRLService.logAgentRun(state.getSessionId(), "dba", temperature);
        log.debug("DBA RL temperature={}", String.format("%.2f", temperature));

        Map<String, Object> payload = Map.of(
                "model", "gpt-5.4-mini",
                "instructions", DBA_SYSTEM,
                "input", List.of(Map.of("role", "user", "content", userContent)),
                "max_output_tokens", 8000,
                "temperature", temperature
        );

        String prdIssues = "";
        String clean = "{}";
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
            String candidate = response.trim()
                    .replaceAll("```json", "")
                    .replaceAll("```", "")
                    .trim();

            JsonNode root = objectMapper.readTree(candidate);
            prdIssues = root.path("prdIssues").asText("");
            ((ObjectNode) root).remove("prdIssues");
            clean = objectMapper.writeValueAsString(root);

            if (!prdIssues.isBlank()) {
                log.warn("DBA → PRD 피드백 발생: {}", prdIssues);
            } else {
                log.info("DBA 검증 통과 — featureList 전체 매핑 확인");
            }

        } catch (Exception e) {
            log.error("DBA 에이전트 실패: {}", e.getMessage());
        }

        log.info("DBA 에이전트 완료 — DB 스키마 생성");

        return state.toBuilder()
                .dbSchema(clean)
                .prdFeedbackFromDba(prdIssues)
                .statusMessage("DBA 에이전트 완료 — DB 스키마 생성")
                .build();
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

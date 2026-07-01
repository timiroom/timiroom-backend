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
 * API 에이전트 — REST API 스펙 설계
 *
 * PM 에이전트의 apiInstruction을 받아 엔드포인트, HTTP 메서드, 요청/응답 스키마를 포함한 API 스펙 생성.
 * DBA 에이전트와 병렬로 실행됨.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiAgent {

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final AgentRLService agentRLService;

    @Value("${spring.ai.openai.api-key}")
    private String foundryApiKey;

    @Value("${app.ai.foundry.responses-url}")
    private String foundryUrl;

    private static final String API_SYSTEM = """
            당신은 시니어 백엔드 개발자입니다.
            아래 지시사항을 바탕으로 REST API 스펙을 JSON 형식으로만 설계하세요.

            응답 형식:
            {
              "endpoints": [
                {
                  "method": "HTTP 메서드 (GET|POST|PUT|PATCH|DELETE)",
                  "path": "/api/경로/{pathParam}",
                  "description": "엔드포인트 한 줄 설명",
                  "authRequired": true,
                  "parameters": [
                    {
                      "in": "path|query|header",
                      "name": "파라미터명",
                      "type": "integer|string|boolean|number",
                      "required": true,
                      "description": "파라미터 설명",
                      "example": "예시 값 (숫자는 숫자형, 문자열은 문자열형으로)"
                    }
                  ],
                  "requestBody": {"필드명": "타입 // 설명"},
                  "successResponse": {"필드명": "타입 // 설명"},
                  "errorCodes": "에러 설명 (예: 404 Not Found — 리소스 없음)"
                }
              ],
              "authentication": "인증 방식 설명",
              "prdIssues": "누락된 API 또는 불명확한 요구사항 (없으면 빈 문자열)"
            }

            parameters 작성 규칙:
            - path에 {id} 같은 변수가 있으면 반드시 parameters에 in=path로 추가
            - GET/DELETE 요청에서 필터·페이징이 있으면 in=query로 추가
            - 각 파라미터에 실제 사용 예시를 example 필드로 반드시 포함
            - id류는 integer, UUID는 string, 페이지번호는 integer, 검색어는 string

            일반 규칙:
            - JSON 외 다른 텍스트는 절대 포함하지 마세요
            - RESTful 설계 원칙을 따르세요
            - authRequired가 true인 경우 JWT Bearer 인증을 사용한다고 가정하세요
            - 기능 목록의 모든 기능에 대한 엔드포인트를 반드시 포함하세요
            - DB 스키마의 테이블과 일치하는 엔드포인트를 설계하세요

            ⚠️ 설계 후 반드시 아래 체크리스트를 검증하세요:
            - [ ] featureList의 모든 기능에 해당하는 API 엔드포인트가 있는가?
            - [ ] path에 {변수}가 있는 엔드포인트에 parameters가 모두 정의되었는가?
            - [ ] 각 파라미터에 example 값이 있는가?
            - [ ] 인증이 필요한 엔드포인트에 authRequired: true가 있는가?

            누락된 기능이 있으면 prdIssues 필드에 명시하세요.
            모두 충족되면 prdIssues는 빈 문자열로 작성하세요.
            """;

    private static final String API_USER_TEMPLATE = """
            지시사항:
            %s

            기능 목록:
            %s

            컨텍스트 (이전 결함 수정 지시 포함):
            %s
            """;

    // ── Collaborative API ───────────────────────────────────────

    /**
     * Round 1: PM 지시 없이 Phase1 컨텍스트를 직접 해석하여 API 스펙 초안 작성.
     */
    public PipelineState executeDraft(PipelineState state) {
        log.info("API 에이전트 초안 작성 시작 — Phase1 컨텍스트 직접 해석");

        String context = state.getContextPrompt() != null ? state.getContextPrompt() : state.getUserQuery();
        String marketSnippet = state.getMarketResearch() != null
                ? state.getMarketResearch().substring(0, Math.min(1500, state.getMarketResearch().length()))
                : "없음";

        String userContent = """
                요구사항:
                %s

                시장 데이터 (상위 요약):
                %s

                위 요구사항을 분석하여 서비스에 필요한 모든 REST API 스펙을 설계하세요.
                PM 별도 지시 없이 요구사항에서 직접 필요한 엔드포인트를 도출하세요.
                """.formatted(context, marketSnippet);

        double temperature = agentRLService.getTemperature("api");
        agentRLService.logAgentRun(state.getSessionId(), "api", temperature);

        Map<String, Object> payload = Map.of(
                "model", "gpt-5.4-mini",
                "instructions", API_SYSTEM,
                "input", List.of(Map.of("role", "user", "content", userContent)),
                "max_output_tokens", 16000,
                "temperature", temperature
        );

        String clean = "{}";
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
            String candidate = response.trim().replaceAll("```json", "").replaceAll("```", "").trim();

            JsonNode root = objectMapper.readTree(repairJson(candidate));
            ((com.fasterxml.jackson.databind.node.ObjectNode) root).remove("prdIssues");
            clean = objectMapper.writeValueAsString(root);

        } catch (Exception e) {
            log.error("API 초안 작성 실패 — 빈 스펙으로 폴백: {}", e.getMessage());
        }

        log.info("API 에이전트 초안 완료");
        return state.toBuilder()
                .apiSpec(clean)
                .prdFeedbackFromApi("")
                .build();
    }

    /**
     * Round 2: PM featureList + PRD 초안 + DBA 스키마 초안을 참조하여 API 스펙 보완 및 불일치 수정.
     */
    public PipelineState refine(PipelineState state) {
        log.info("API 에이전트 피드백 반영 수정 시작");

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
                [당신의 초안 API 스펙]
                %s

                [PM 기능 목록 — 모든 기능에 엔드포인트가 매핑되어야 함]
                %s

                [PRD 요구사항 (주요 부분)]
                %s

                [DBA 스키마 초안 — 테이블 구조와 API 엔드포인트 일치 확인]
                %s
                %s
                위 결과물들과 당신의 초안을 비교하여 수정된 최종 API 스펙을 작성하세요.
                수정 기준:
                1. QA 지적 결함이 있으면 최우선으로 수정 (누락 엔드포인트 추가, DBA 스키마와 불일치 수정)
                2. PM 기능 목록에서 엔드포인트가 없는 기능이 있으면 추가
                3. PRD 요구사항에서 API 스펙에 반영되지 않은 기능이 있으면 추가
                4. DBA 스키마의 테이블과 매핑되지 않는 엔드포인트는 수정
                """.formatted(
                        state.getApiSpec() != null ? state.getApiSpec() : "{}",
                        featureStr,
                        prdSnippet,
                        state.getDbSchema() != null
                                ? state.getDbSchema().substring(0, Math.min(3000, state.getDbSchema().length()))
                                : "(DBA 초안 없음)",
                        qaFeedbackSection
        );

        double temperature = agentRLService.getCurrentTemperature("api");

        Map<String, Object> payload = Map.of(
                "model", "gpt-5.4-mini",
                "instructions", API_SYSTEM,
                "input", List.of(Map.of("role", "user", "content", userContent)),
                "max_output_tokens", 16000,
                "temperature", temperature
        );

        String clean = state.getApiSpec() != null ? state.getApiSpec() : "{}";
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
            String candidate = response.trim().replaceAll("```json", "").replaceAll("```", "").trim();

            JsonNode root = objectMapper.readTree(repairJson(candidate));
            ((com.fasterxml.jackson.databind.node.ObjectNode) root).remove("prdIssues");
            clean = objectMapper.writeValueAsString(root);

        } catch (Exception e) {
            log.error("API 수정 실패 — 이전 스펙 유지: {}", e.getMessage());
        }

        log.info("API 에이전트 수정 완료");
        return state.toBuilder()
                .apiSpec(clean)
                .prdFeedbackFromApi("")
                .build();
    }

    // ── 기존 Sequential API ──────────────────────────────────────

    public PipelineState execute(PipelineState state) {
        log.info("API 에이전트 시작");

        String featureListStr = "- " + String.join("\n- ", state.getFeatureList());
        String context = state.getContextPrompt() != null ? state.getContextPrompt() : "";
        String userContent = String.format(API_USER_TEMPLATE,
                state.getApiInstruction(), featureListStr, context);

        // RL: epsilon-greedy로 temperature 선택 후 기록
        double temperature = agentRLService.getTemperature("api");
        agentRLService.logAgentRun(state.getSessionId(), "api", temperature);
        log.debug("API RL temperature={}", String.format("%.2f", temperature));

        Map<String, Object> payload = Map.of(
                "model", "gpt-5.4-mini",
                "instructions", API_SYSTEM,
                "input", List.of(Map.of("role", "user", "content", userContent)),
                "max_output_tokens", 16000,
                "temperature", temperature
        );

        String prdIssues = "";
        String clean = "{}";
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
            String candidate = response.trim()
                    .replaceAll("```json", "")
                    .replaceAll("```", "")
                    .trim();

            JsonNode root = objectMapper.readTree(repairJson(candidate));
            prdIssues = root.path("prdIssues").asText("");
            ((ObjectNode) root).remove("prdIssues");
            clean = objectMapper.writeValueAsString(root);

            if (!prdIssues.isBlank()) {
                log.warn("API → PRD 피드백 발생: {}", prdIssues);
            } else {
                log.info("API 검증 통과 — featureList 전체 엔드포인트 매핑 확인");
            }

        } catch (Exception e) {
            log.error("API 에이전트 실패: {}", e.getMessage());
        }

        log.info("API 에이전트 완료 — API 스펙 생성");

        return state.toBuilder()
                .apiSpec(clean)
                .prdFeedbackFromApi(prdIssues)
                .statusMessage("API 에이전트 완료 — API 스펙 생성")
                .build();
    }

    /**
     * LLM 응답이 max_output_tokens에서 잘린 경우 JSON을 복구한다.
     * 열린 괄호/따옴표를 닫아서 파서가 읽을 수 있는 상태로 만든다.
     */
    private String repairJson(String raw) {
        if (raw == null || raw.isBlank()) return "{}";
        String s = raw.trim();

        // 열린 문자열(따옴표 미닫힘) 제거: 마지막 완전한 key:value 쌍 이후를 잘라냄
        // 마지막 완성된 ',' 또는 '{' '['  직전까지만 사용
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        int lastSafePos = 0;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;

            if (c == '{' || c == '[') { depth++; }
            else if (c == '}' || c == ']') {
                depth--;
                lastSafePos = i + 1;
            } else if (c == ',' && depth > 0) {
                lastSafePos = i;
            }
        }

        // 잘린 경우: 마지막 안전 위치까지 자르고 닫는 괄호 보완
        if (inString || depth > 0) {
            log.warn("JSON 잘림 감지 — 복구 시도 (lastSafePos={})", lastSafePos);
            s = s.substring(0, lastSafePos);
            // 닫는 괄호 보완
            StringBuilder sb = new StringBuilder(s);
            // 현재 depth 재계산
            int d = 0;
            boolean ins = false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"') ins = !ins;
                if (ins) continue;
                if (c == '{' || c == '[') d++;
                else if (c == '}' || c == ']') d--;
            }
            while (d-- > 0) sb.append(d % 2 == 0 ? "]}" : "}");
            s = sb.toString();
        }

        return s;
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

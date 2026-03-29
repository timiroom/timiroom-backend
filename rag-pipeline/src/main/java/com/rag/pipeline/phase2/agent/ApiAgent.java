package com.rag.pipeline.phase2.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag.pipeline.phase2.state.PipelineState;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * API 에이전트 — REST API 스펙 설계
 *
 * 역할:
 *   PM 에이전트의 apiInstruction을 받아
 *   엔드포인트, HTTP 메서드, 요청/응답 스키마를 포함한 API 스펙 생성
 *
 * 워크플로우 지원:
 *   설계 후 featureList 매핑 검증 → PRD 누락 시 prdFeedbackFromApi에 피드백 저장
 *   → OrchestrationGraph가 감지하면 PrdAgent로 rollback
 *
 * GPT-4o 사용
 * DBA 에이전트와 병렬로 실행됨
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiAgent {

    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;
    private ChatClient chatClient;

    @PostConstruct
    private void init() {
        this.chatClient = chatClientBuilder
                .defaultOptions(
                        org.springframework.ai.openai.OpenAiChatOptions.builder()
                                .withModel("gpt-4o")
                                .withTemperature(0.1)
                                .build()
                )
                .build();
    }


    private static final String API_PROMPT = """
            당신은 시니어 백엔드 개발자입니다.
            아래 지시사항을 바탕으로 REST API 스펙을 JSON 형식으로만 설계하세요.
            
            응답 형식:
            {
              "endpoints": [
                {
                  "method": "HTTP 메서드",
                  "patprivate ChatClient chatClient;h": "/api/경로",
                  "description": "엔드포인트 설명",
                  "request": {
                    "headers": {"헤더명": "타입"},
                    "body": {"필드명": "타입"}
                  },
                  "response": {
                    "success": {"필드명": "타입"},
                    "error": "에러 설명"
                  }
                }
              ],
              "authentication": "인증 방식 설명",
              "prdIssues": "누락된 API 또는 불명확한 요구사항 (없으면 빈 문자열 \\"\\")"
            }
            
            규칙:
            - JSON 외 다른 텍스트는 절대 포함하지 마세요
            - RESTful 설계 원칙을 따르세요
            - 인증이 필요한 엔드포인트는 Authorization 헤더를 명시하세요
            - 기능 목록의 모든 기능에 대한 엔드포인트를 반드시 포함하세요
            - DB 스키마의 테이블과 일치하는 엔드포인트를 설계하세요
            
            ⚠️ 설계 후 반드시 아래 체크리스트를 검증하세요:
            - [ ] featureList의 모든 기능에 해당하는 API 엔드포인트가 있는가?
            - [ ] 각 엔드포인트의 request/response가 명확한가?
            - [ ] 인증이 필요한 엔드포인트에 Authorization 헤더가 있는가?
            
            누락된 기능이 있으면 prdIssues 필드에 명시하세요.
            모두 충족되면 prdIssues는 빈 문자열로 작성하세요.
            
            지시사항:
            %s
            
            기능 목록:
            %s
            
            컨텍스트 (이전 결함 수정 지시 포함):
            %s
            """;

    public PipelineState execute(PipelineState state) {
        log.info("API 에이전트 시작");

        String featureListStr = "- " + String.join("\n- ", state.getFeatureList());
        String context = state.getContextPrompt() != null
                ? state.getContextPrompt() : "";

        String response = chatClient
                .prompt(String.format(API_PROMPT,
                        state.getApiInstruction(),
                        featureListStr,
                        context))
                .call()
                .content();

        String clean = response.trim()
                .replaceAll("```json", "")
                .replaceAll("```", "")
                .trim();

        // prdIssues 추출 후 apiSpec에서 제거
        String prdIssues = "";
        try {
            JsonNode root = objectMapper.readTree(clean);
            prdIssues = root.path("prdIssues").asText("");

            // prdIssues 필드는 apiSpec에 저장할 필요 없으므로 제거
            ((ObjectNode) root).remove("prdIssues");
            clean = objectMapper.writeValueAsString(root);

            if (!prdIssues.isBlank()) {
                log.warn("API → PRD 피드백 발생: {}", prdIssues);
            } else {
                log.info("API 검증 통과 — featureList 전체 엔드포인트 매핑 확인");
            }

        } catch (Exception e) {
            log.warn("API 응답 파싱 중 prdIssues 추출 실패: {}", e.getMessage());
        }

        log.info("API 에이전트 완료 — API 스펙 생성");

        return state.toBuilder()
                .apiSpec(clean)
                .prdFeedbackFromApi(prdIssues)   // ← rollback 트리거
                .statusMessage("API 에이전트 완료 — API 스펙 생성")
                .build();
    }
}
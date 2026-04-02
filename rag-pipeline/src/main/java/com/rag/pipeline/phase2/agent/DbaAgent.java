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
 * DBA 에이전트 — DB 스키마 설계
 *
 * 역할:
 *   PM 에이전트의 dbaInstruction을 받아
 *   테이블 구조, 관계, 인덱스를 포함한 DB 스키마를 생성
 *
 * 워크플로우 지원:
 *   설계 후 featureList 매핑 검증 → PRD 누락 시 prdFeedbackFromDba에 피드백 저장
 *   → OrchestrationGraph가 감지하면 PrdAgent로 rollback
 *
 * GPT-4o 사용
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbaAgent {

    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;

    private ChatClient chatClient;

    @PostConstruct
    private void init() {
        this.chatClient = chatClientBuilder
                .defaultOptions(
                        org.springframework.ai.openai.OpenAiChatOptions.builder()
                                .withModel("gpt-4o-mini")
                                .withTemperature(0.1)
                                .build()
                )
                .build();
    }


    private static final String DBA_PROMPT = """
            당신은 시니어 DBA입니다.
            아래 지시사항을 바탕으로 DB 스키마를 JSON 형식으로만 설계하세요.
            
            응답 형식:
            {
              "tables": [
                {
                  "name": "테이블명",
                  "columns": [
                    {"name": "컬럼명", "type": "타입", "constraints": "제약조건"},
                    ...
                  ],
                  "indexes": ["인덱스 설명"]
                }
              ],
              "relationships": ["관계 설명"],
              "prdIssues": "PRD에서 누락되거나 불명확한 기능 (없으면 빈 문자열 \\"\\")"
            }
            
            규칙:
            - JSON 외 다른 텍스트는 절대 포함하지 마세요
            - 각 테이블에 반드시 id(PK), created_at, updated_at 컬럼을 포함하세요
            - 외래키 관계를 명확히 표현하세요
            - 기능 목록의 모든 기능이 테이블에 반영되어야 합니다
            - API에서 필요로 하는 모든 테이블과 컬럼을 반드시 포함하세요
            - relationships 배열에 반드시 카디널리티를 명시하세요
            - 형식: "테이블A (1:N) 테이블B" 또는 "테이블A (1:1) 테이블B" 또는 "테이블A (N:M) 테이블B"
            - 통계/요약 테이블도 데이터를 집계하는 원본 테이블과 relationships에 명시하세요
            
            ⚠️ 설계 후 반드시 아래 체크리스트를 검증하세요:
            - [ ] featureList의 모든 기능이 최소 1개 이상의 테이블과 매핑되는가?
            - [ ] 각 테이블에 PK, created_at, updated_at이 있는가?
            - [ ] 외래키 관계가 relationships에 모두 명시되어 있는가?
            
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
        log.info("DBA 에이전트 시작");

        String featureListStr = "- " + String.join("\n- ", state.getFeatureList());
        String context = state.getContextPrompt() != null ? state.getContextPrompt() : "";

        String response = chatClient
                .prompt(String.format(DBA_PROMPT,
                        state.getDbaInstruction(),
                        featureListStr,
                        context))
                .call()
                .content();

        String clean = response.trim()
                .replaceAll("```json", "")
                .replaceAll("```", "")
                .trim();

        // prdIssues 추출 후 dbSchema에서 제거
        String prdIssues = "";
        try {
            JsonNode root = objectMapper.readTree(clean);
            prdIssues = root.path("prdIssues").asText("");

            // prdIssues 필드는 dbSchema에 저장할 필요 없으므로 제거
            ((ObjectNode) root).remove("prdIssues");
            clean = objectMapper.writeValueAsString(root);

            if (!prdIssues.isBlank()) {
                log.warn("DBA → PRD 피드백 발생: {}", prdIssues);
            } else {
                log.info("DBA 검증 통과 — featureList 전체 매핑 확인");
            }

        } catch (Exception e) {
            log.warn("DBA 응답 파싱 중 prdIssues 추출 실패: {}", e.getMessage());
        }

        log.info("DBA 에이전트 완료 — DB 스키마 생성");

        return state.toBuilder()
                .dbSchema(clean)
                .prdFeedbackFromDba(prdIssues)   // ← rollback 트리거
                .statusMessage("DBA 에이전트 완료 — DB 스키마 생성")
                .build();
    }
}
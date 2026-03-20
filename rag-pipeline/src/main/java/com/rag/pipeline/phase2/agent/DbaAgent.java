package com.rag.pipeline.phase2.agent;

import com.rag.pipeline.phase2.state.PipelineState;
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
 * QA 재시도 시 발견된 결함을 프롬프트에 강하게 재주입하여
 *   누락된 테이블, 컬럼을 반드시 포함하도록 유도
 *
 * GPT-4o-mini 사용 — 단순 생성 작업, 비용 최적화
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbaAgent {

    private final ChatClient.Builder chatClientBuilder;

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
              "relationships": ["관계 설명"]
            }
            
            규칙:
            - JSON 외 다른 텍스트는 절대 포함하지 마세요
            - 각 테이블에 반드시 id(PK), created_at, updated_at 컬럼을 포함하세요
            - 외래키 관계를 명확히 표현하세요
            - 기능 목록의 모든 기능이 테이블에 반영되어야 합니다
            - API에서 필요로 하는 모든 테이블과 컬럼을 반드시 포함하세요
            - relationships 배열에 반드시 카디널리티를 명시하세요
            - 형식: "테이블A (1:N) 테이블B" 또는 "테이블A (1:1) 테이블B" 또는 "테이블A (N:M) 테이블B"
            - 예시:
              "users (1:N) loans",
              "users (1:N) reservations",
              "book_master (1:N) physical_books",
              "transactions (1:1) fines"
            - 통계/요약 테이블도 데이터를 집계하는 원본 테이블과 relationships에 명시하세요
              예: "loans (N:1) statistics_summary", "users (N:1) statistics_summary"
            
            지시사항:
            %s
            
            기능 목록:
            %s
            
            컨텍스트 (이전 결함 수정 지시 포함):
            %s
            """;

    /**
     * DBA 에이전트 실행
     */
    public PipelineState execute(PipelineState state) {
        log.info("DBA 에이전트 시작");

        ChatClient chatClient = chatClientBuilder
                .defaultOptions(
                        org.springframework.ai.openai.OpenAiChatOptions.builder()
                                .withModel("gpt-4o")
                                .withTemperature(0.1)
                                .build()
                )
                .build();

        String featureListStr = String.join("\n- ", state.getFeatureList());
        featureListStr = "- " + featureListStr;

        // contextPrompt에 QA 결함 재주입 내용이 포함되어 있음
        String context = state.getContextPrompt() != null
                ? state.getContextPrompt() : "";

        String response = chatClient
                .prompt(String.format(DBA_PROMPT,
                        state.getDbaInstruction(),
                        featureListStr,
                        context))
                .call()
                .content();

        String dbSchema = response.trim()
                .replaceAll("```json", "")
                .replaceAll("```", "")
                .trim();

        log.info("DBA 에이전트 완료 — DB 스키마 생성");

        return state.toBuilder()
                .dbSchema(dbSchema)
                .statusMessage("DBA 에이전트 완료 — DB 스키마 생성")
                .build();
    }
}
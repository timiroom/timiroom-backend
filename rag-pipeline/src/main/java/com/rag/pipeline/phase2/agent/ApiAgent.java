package com.rag.pipeline.phase2.agent;

import com.rag.pipeline.phase2.state.PipelineState;
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
 * QA 재시도 시 발견된 결함을 프롬프트에 강하게 재주입하여
 *   누락된 엔드포인트를 반드시 포함하도록 유도
 *
 * GPT-4o-mini 사용 — 단순 생성 작업, 비용 최적화
 * DBA 에이전트와 병렬로 실행됨
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiAgent {

    private final ChatClient.Builder chatClientBuilder;

    private static final String API_PROMPT = """
            당신은 시니어 백엔드 개발자입니다.
            아래 지시사항을 바탕으로 REST API 스펙을 JSON 형식으로만 설계하세요.
            
            응답 형식:
            {
              "endpoints": [
                {
                  "method": "HTTP 메서드",
                  "path": "/api/경로",
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
              "authentication": "인증 방식 설명"
            }
            
            규칙:
            - JSON 외 다른 텍스트는 절대 포함하지 마세요
            - RESTful 설계 원칙을 따르세요
            - 인증이 필요한 엔드포인트는 Authorization 헤더를 명시하세요
            - 기능 목록의 모든 기능에 대한 엔드포인트를 반드시 포함하세요
            - DB 스키마의 테이블과 일치하는 엔드포인트를 설계하세요
            
            지시사항:
            %s
            
            기능 목록:
            %s
            
            컨텍스트 (이전 결함 수정 지시 포함):
            %s
            """;

    /**
     * API 에이전트 실행
     */
    public PipelineState execute(PipelineState state) {
        log.info("API 에이전트 시작");

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
                .prompt(String.format(API_PROMPT,
                        state.getApiInstruction(),
                        featureListStr,
                        context))
                .call()
                .content();

        String apiSpec = response.trim()
                .replaceAll("```json", "")
                .replaceAll("```", "")
                .trim();

        log.info("API 에이전트 완료 — API 스펙 생성");

        return state.toBuilder()
                .apiSpec(apiSpec)
                .statusMessage("API 에이전트 완료 — API 스펙 생성")
                .build();
    }
}
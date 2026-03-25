package com.rag.pipeline.phase2.agent;

import com.rag.pipeline.phase2.state.PipelineState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PM 에이전트 — 기능 도출 및 하위 에이전트 지시사항 생성
 *
 * 역할:
 *   1. 사용자 요구사항 분석 → 기능 목록 도출
 *   2. DBA 에이전트에 내릴 지시사항 생성
 *   3. API 에이전트에 내릴 지시사항 생성
 *
 * GPT-4o 사용 — 복잡한 다단계 추론 필요
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PmAgent {

    private final ChatClient.Builder chatClientBuilder;

    private static final String PM_PROMPT = """
            당신은 시니어 소프트웨어 아키텍트입니다.
            아래 요구사항을 분석하여 JSON 형식으로만 응답하세요.
            
            응답 형식:
            {
              "featureList": ["기능1", "기능2", "기능3"],
              "dbaInstruction": "DBA에게 전달할 DB 설계 지시사항",
              "apiInstruction": "API 개발자에게 전달할 API 설계 지시사항"
            }
            
            규칙:
            - featureList는 구체적인 기능 단위로 작성하세요 (최대 10개)
            - dbaInstruction은 필요한 테이블, 관계, 제약조건을 명시하세요
            - apiInstruction은 필요한 엔드포인트와 인증 방식을 명시하세요
            - JSON 외 다른 텍스트는 절대 포함하지 마세요
            
            요구사항:
            %s
            """;

    /**
     * PM 에이전트 실행
     * State에서 contextPrompt를 읽어 기능 목록과 지시사항을 생성 후 새 State 반환
     */
    public PipelineState execute(PipelineState state) {
        log.info("PM 에이전트 시작 — query: '{}'", state.getUserQuery());

        ChatClient chatClient = chatClientBuilder
                .defaultOptions(
                        org.springframework.ai.openai.OpenAiChatOptions.builder()
                                .withModel("gpt-4o")
                                .withTemperature(0.1)
                                .build()
                )
                .build();

        String response = chatClient
                .prompt(String.format(PM_PROMPT, state.getContextPrompt()))
                .call()
                .content();

        // JSON 파싱
        PmResponse parsed = parseResponse(response);

        log.info("PM 에이전트 완료 — {} 기능 도출", parsed.featureList().size());

        return state.toBuilder()
                .featureList(parsed.featureList())
                .dbaInstruction(parsed.dbaInstruction())
                .apiInstruction(parsed.apiInstruction())
                .statusMessage("PM 에이전트 완료 — 기능 목록 도출")
                .build();
    }

    private PmResponse parseResponse(String response) {
        try {
            String clean = response.trim()
                    .replaceAll("```json", "")
                    .replaceAll("```", "")
                    .trim();

            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(clean);

            List<String> featureList = new ArrayList<>();
            root.path("featureList").forEach(n -> featureList.add(n.asText()));

            String dbaInstruction = root.path("dbaInstruction").asText("");
            String apiInstruction = root.path("apiInstruction").asText("");

            return new PmResponse(featureList, dbaInstruction, apiInstruction);

        } catch (Exception e) {
            log.error("PM 에이전트 응답 파싱 실패 — {}", e.getMessage());
            return new PmResponse(
                    List.of("파싱 실패"),
                    "파싱 실패: " + e.getMessage(),
                    "파싱 실패: " + e.getMessage()
            );
        }
    }

    record PmResponse(
            List<String> featureList,
            String dbaInstruction,
            String apiInstruction
    ) {}
}
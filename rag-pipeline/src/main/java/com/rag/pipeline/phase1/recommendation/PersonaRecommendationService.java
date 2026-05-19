package com.rag.pipeline.phase1.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PersonaRecommendationService {

    private final AnthropicChatModel anthropicChatModel;
    private final ObjectMapper objectMapper;

    public PersonaRecommendationResponse recommend(PersonaRecommendationRequest req) {
        try {
            var pd = req.problemDefinition();
            String response = anthropicChatModel.call(new Prompt(
                java.util.List.of(
                    new SystemMessage("""
                        당신은 UX 리서처입니다.
                        서비스 정보를 보고 가장 핵심적인 타겟 유저 페르소나를 추천하세요.
                        반드시 JSON만 반환하고 코드 블록이나 다른 텍스트는 포함하지 마세요.
                        """),
                    new UserMessage(String.format("""
                        프로젝트명: %s
                        설명: %s
                        핵심 문제: %s
                        현재 해결 방식: %s
                        이상적인 상태: %s

                        위 서비스를 가장 필요로 할 타겟 유저 2명의 페르소나를 추천해주세요.
                        실제로 존재할 법한 구체적인 사람으로 작성해주세요.

                        {"personas":[{"persona":"","usageEnvironment":"","biggestPainPoint":""}]}
                        """,
                        req.projectName(),
                        req.projectDescription(),
                        pd.currentPainPoint(),
                        pd.currentSolution(),
                        pd.idealState()))
                ),
                AnthropicChatOptions.builder()
                    .withModel("claude-sonnet-4-20250514")
                    .withMaxTokens(512)
                    .withTemperature(0.4)
                    .build()
            )).getResult().getOutput().getContent();

            return objectMapper.readValue(response.trim(), PersonaRecommendationResponse.class);

        } catch (Exception e) {
            log.warn("페르소나 추천 실패: {}", e.getMessage());
            return PersonaRecommendationResponse.empty();
        }
    }
}

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

import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureRecommendationService {

    private final AnthropicChatModel anthropicChatModel;
    private final ObjectMapper objectMapper;

    public FeatureRecommendationResponse recommend(FeatureRecommendationRequest req) {
        try {
            String response = anthropicChatModel.call(new Prompt(
                java.util.List.of(
                    new SystemMessage("""
                        당신은 프로덕트 매니저입니다.
                        프로젝트 정보를 보고 필요한 기능을 MoSCoW 우선순위로 추천하세요.
                        반드시 JSON만 반환하고 코드 블록이나 다른 텍스트는 포함하지 마세요.
                        """),
                    new UserMessage(buildPrompt(req))
                ),
                AnthropicChatOptions.builder()
                    .withModel("claude-sonnet-4-20250514")
                    .withMaxTokens(1024)
                    .withTemperature(0.3)
                    .build()
            )).getResult().getOutput().getContent();

            return objectMapper.readValue(response.trim(), FeatureRecommendationResponse.class);

        } catch (Exception e) {
            log.warn("기능 추천 실패: {}", e.getMessage());
            return FeatureRecommendationResponse.empty();
        }
    }

    private String buildPrompt(FeatureRecommendationRequest req) {
        var pd = req.problemDefinition();
        String users = req.targetUsers() == null || req.targetUsers().isEmpty()
            ? "정보 없음"
            : req.targetUsers().stream()
                .map(u -> u.persona() + " / " + u.biggestPainPoint())
                .collect(Collectors.joining(", "));

        return String.format("""
            프로젝트명: %s
            설명: %s
            플랫폼: %s
            기술 스택: %s
            핵심 문제: %s
            타겟 유저: %s

            이 서비스에 필요한 기능을 MoSCoW 우선순위로 5~8개 추천해주세요.
            - MUST: MVP에 반드시 필요한 기능
            - SHOULD: 가능하면 포함할 기능
            - COULD: 여유 있으면 포함할 기능
            - WONT: 명시적으로 제외할 기능

            {"features":[{"priority":"MUST","featureName":"","description":""}]}
            """,
            req.projectName(),
            req.projectDescription(),
            req.platform(),
            req.techStack() != null ? String.join(", ", req.techStack()) : "",
            pd.currentPainPoint(),
            users
        );
    }
}

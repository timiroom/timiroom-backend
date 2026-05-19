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
public class TechStackRecommendationService {

    private final AnthropicChatModel anthropicChatModel;
    private final ObjectMapper objectMapper;

    public TechStackResponse recommend(String projectName, String desc, String platform) {
        try {
            String response = anthropicChatModel.call(new Prompt(
                java.util.List.of(
                    new SystemMessage("""
                        당신은 소프트웨어 아키텍처 전문가입니다.
                        반드시 JSON만 반환하고 코드 블록이나 다른 텍스트는 포함하지 마세요.
                        """),
                    new UserMessage(String.format("""
                        프로젝트명: %s
                        설명: %s
                        플랫폼: %s

                        아래 JSON 형식으로 파트별 기술 스택을 추천해주세요.
                        각 파트에 2~3개를 추천하세요.
                        플랫폼이 WEB이면 mobile은 빈 배열로 반환하세요.
                        플랫폼이 APP이면 frontend는 빈 배열로 반환하세요.

                        {"frontend":[],"backend":[],"database":[],"devops":[],"mobile":[]}
                        """, projectName, desc, platform))
                ),
                AnthropicChatOptions.builder()
                    .withModel("claude-sonnet-4-20250514")
                    .withMaxTokens(512)
                    .withTemperature(0.3)
                    .build()
            )).getResult().getOutput().getContent();

            return objectMapper.readValue(response.trim(), TechStackResponse.class);

        } catch (Exception e) {
            log.warn("기술 스택 추천 실패, 기본값 반환: {}", e.getMessage());
            return TechStackResponse.defaultFor(platform);
        }
    }
}

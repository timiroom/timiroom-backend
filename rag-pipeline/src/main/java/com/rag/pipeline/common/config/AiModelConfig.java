package com.rag.pipeline.common.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * OpenAI + Anthropic 두 ChatModel이 공존할 때 ChatClientAutoConfiguration이
 * 어느 모델을 써야 할지 결정하지 못하는 문제를 해결.
 *
 * ChatClient.Builder를 명시적으로 OpenAiChatModel 기반으로 등록하면
 * QueryExpansionService / RerankerService / Phase2 에이전트 등
 * ChatClient.Builder 의존 빈들이 OpenAI를 쓰게 된다.
 */
@Configuration
public class AiModelConfig {

    @Primary
    @Bean
    public ChatClient.Builder chatClientBuilder(OpenAiChatModel openAiChatModel) {
        return ChatClient.builder(openAiChatModel);
    }
}

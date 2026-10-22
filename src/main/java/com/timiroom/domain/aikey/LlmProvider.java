package com.timiroom.domain.aikey;

/**
 * 사용자가 키를 넣을 수 있는 AI 제공자. 모두 OpenAI 호환 주소로 부른다.
 * CUSTOM은 Friendli·OpenRouter처럼 OpenAI 호환 주소를 직접 넣는 경우다.
 */
public enum LlmProvider {
    OPENAI("https://api.openai.com/v1"),
    ANTHROPIC("https://api.anthropic.com/v1"),
    GOOGLE("https://generativelanguage.googleapis.com/v1beta/openai"),
    CUSTOM(null);

    private final String defaultBaseUrl;

    LlmProvider(String defaultBaseUrl) {
        this.defaultBaseUrl = defaultBaseUrl;
    }

    public String defaultBaseUrl() {
        return defaultBaseUrl;
    }
}

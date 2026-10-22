package com.timiroom.infra.ragpipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * AI 서버가 이유를 알려 준 실패(내 AI 키 오류, 크레딧 소진, 요청 몰림 등).
 * 화면은 code로 안내와 바로가기 버튼을 고르고, message를 그대로 보여 준다.
 */
public class AiServiceException extends RuntimeException {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final int status;
    private final String code;

    public AiServiceException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }

    /** AI 서버 응답에 AI_로 시작하는 코드가 있으면 그 안내를, 없으면 fallback 문구를 쓴다. */
    public static AiServiceException from(WebClientResponseException e, String fallback) {
        int status = e.getStatusCode().value();
        try {
            JsonNode body = MAPPER.readTree(e.getResponseBodyAsString());
            String code = body.path("code").asText("");
            String message = body.path("message").asText("");
            if (code.startsWith("AI_") && !message.isBlank() && message.length() <= 300) {
                return new AiServiceException(status, code, message);
            }
        } catch (Exception notJson) {
            // AI 서버가 다른 형식으로 답하면 아래 기본 안내를 쓴다
        }
        return new AiServiceException(status >= 500 ? 503 : status, "AI_PROVIDER_ERROR", fallback);
    }
}

package com.timiroom.infra.ragpipeline;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class AiServiceExceptionTest {
    private static WebClientResponseException response(int status,String body) {
        return WebClientResponseException.create(status,"x",HttpHeaders.EMPTY,body.getBytes(StandardCharsets.UTF_8),StandardCharsets.UTF_8);
    }
    @Test void keepsTheAiServersKoreanMessageAndCode() {
        var e=AiServiceException.from(response(402,"{\"code\":\"AI_KEY_QUOTA_EXCEEDED\",\"message\":\"연결한 AI 키의 크레딧을 모두 썼습니다.\"}"),"기본");
        assertThat(e.status()).isEqualTo(402);
        assertThat(e.code()).isEqualTo("AI_KEY_QUOTA_EXCEEDED");
        assertThat(e.getMessage()).isEqualTo("연결한 AI 키의 크레딧을 모두 썼습니다.");
    }
    @Test void unknownBodiesFallBackWithoutLeakingThem() {
        var e=AiServiceException.from(response(500,"Traceback: secret stuff"),"AI 응답을 받지 못했습니다.");
        assertThat(e.status()).isEqualTo(503);
        assertThat(e.code()).isEqualTo("AI_PROVIDER_ERROR");
        assertThat(e.getMessage()).isEqualTo("AI 응답을 받지 못했습니다.");
        // AI_로 시작하지 않는 코드(내부 오류 코드)는 그대로 보여 주지 않는다
        assertThat(AiServiceException.from(response(400,"{\"code\":\"COMMON_001\",\"message\":\"field x required\"}"),"기본").getMessage()).isEqualTo("기본");
    }
}

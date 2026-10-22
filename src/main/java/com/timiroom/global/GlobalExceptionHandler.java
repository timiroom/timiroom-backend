package com.timiroom.global;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** AI 사용 한도 초과. 화면은 code·resetAt·plan을 보고 안내와 업그레이드 버튼을 보여 준다. */
    @ExceptionHandler(com.timiroom.domain.usage.service.UsageLimitExceededException.class)
    public ResponseEntity<Map<String, String>> handleUsageLimit(com.timiroom.domain.usage.service.UsageLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                "error", e.getMessage(),
                "detail", e.getMessage(),
                "code", "USAGE_LIMIT_EXCEEDED",
                "resetAt", e.getResetAt().toString(),
                "plan", e.getPlan()));
    }

    /** AI 서버가 이유를 알려 준 실패. 화면은 code로 안내와 바로가기를 고른다(예: AI_KEY_INVALID → 내 AI 키 설정). */
    @ExceptionHandler(com.timiroom.infra.ragpipeline.AiServiceException.class)
    public ResponseEntity<Map<String, String>> handleAiService(com.timiroom.infra.ragpipeline.AiServiceException e) {
        log.warn("AI 서비스 오류 {}: {}", e.code(), e.getMessage());
        return ResponseEntity.status(e.status()).body(Map.of(
                "error", e.getMessage(),
                "message", e.getMessage(),
                "code", e.code()));
    }

    /** RAG 파이프라인 서비스 연결 불가 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleServiceUnavailable(IllegalStateException e) {
        log.error("서비스 사용 불가: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", e.getMessage()));
    }

    /** 기타 예상치 못한 예외 */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntimeException(RuntimeException e) {
        log.error("처리되지 않은 예외: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "서버 내부 오류가 발생했습니다."));
    }
}

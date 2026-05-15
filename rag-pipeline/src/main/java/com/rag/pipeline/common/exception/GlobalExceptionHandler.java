package com.rag.pipeline.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.concurrent.TimeoutException;

@RestControllerAdvice  // 모든 @RestController에 적용
@Slf4j
public class GlobalExceptionHandler {

    // @Valid 검증 실패 시 (query가 비어있거나 너무 짧을 때)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException e) {

        String message = e.getBindingResult().getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage)
                .findFirst()
                .orElse(ErrorCode.INVALID_INPUT.getMessage());

        log.warn("[VALIDATION FAILED] {}", message);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)  // 400
                .body(ErrorResponse.of(ErrorCode.INVALID_INPUT, message));
    }

    // GPT 응답 타임아웃
    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<ErrorResponse> handleTimeout(TimeoutException e) {
        log.error("[TIMEOUT] {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.GATEWAY_TIMEOUT)  // 504
                .body(ErrorResponse.of(ErrorCode.PIPELINE_TIMEOUT));
    }

    // 그 외 모든 예외 (최후의 안전망)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("[UNHANDLED ERROR] {}", e.getMessage(), e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)  // 500
                .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
    }
}
package com.rag.pipeline.common.exception;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class ErrorResponse {

    private final String code;           // 에러 코드 (예: PIPELINE_001)
    private final String message;        // 에러 메시지
    private final LocalDateTime timestamp; // 에러 발생 시각

    // ErrorCode만으로 생성
    public static ErrorResponse of(ErrorCode errorCode) {
        return ErrorResponse.builder()
                .code(errorCode.getCode())
                .message(errorCode.getMessage())
                .timestamp(LocalDateTime.now())
                .build();
    }

    // ErrorCode + 커스텀 메시지로 생성 (검증 실패 시 상세 메시지 전달)
    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return ErrorResponse.builder()
                .code(errorCode.getCode())
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
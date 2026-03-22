package com.rag.pipeline.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)  // null 필드는 응답에서 제외
public class ApiResponse<T> {

    private final boolean success;   // 성공 여부
    private final String code;       // 응답 코드
    private final String message;    // 메시지
    private final T data;            // 실제 데이터 (에러 시 null)

    // 성공 응답 (data 있음)
    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .code("SUCCESS")
                .data(data)
                .build();
    }

    // 성공 응답 (message 커스텀)
    public static <T> ApiResponse<T> success(T data, String message) {
        return ApiResponse.<T>builder()
                .success(true)
                .code("SUCCESS")
                .message(message)
                .data(data)
                .build();
    }

    // 에러 응답
    public static <T> ApiResponse<T> error(String code, String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .code(code)
                .message(message)
                .build();
    }
}

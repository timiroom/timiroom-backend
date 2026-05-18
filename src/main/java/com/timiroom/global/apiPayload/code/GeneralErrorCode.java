package com.timiroom.global.apiPayload.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum GeneralErrorCode implements BaseErrorCode {

    BAD_REQUEST(HttpStatus.BAD_REQUEST,
            "COMMON400_1",
            "잘못된 요청입니다."),

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED,
            "COMMON402_1",
            "인증되지 않았습니다."),

    FORBIDDEN(HttpStatus.FORBIDDEN,
            "COMMON403_1",
            ""),

    NOT_FOUND(HttpStatus.NOT_FOUND,
            "COMMON401_1",
            "해당 리소스를 찾을 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR,
                "COMMON500_1",
                    "");

    private final HttpStatus status;
    private final String code;
    private final String message;
}

package com.timiroom.domain.member.exception.code;

import com.timiroom.global.apiPayload.code.BaseSuccessCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MemberSuccessCode implements BaseSuccessCode {

    OK(HttpStatus.OK,
            "COMMON200_1",
            "");

    private final HttpStatus status;
    private final String message;
    private final String code;
}

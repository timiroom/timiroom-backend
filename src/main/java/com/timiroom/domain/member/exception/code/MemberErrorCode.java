package com.timiroom.domain.member.exception.code;

import com.timiroom.global.apiPayload.code.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements BaseErrorCode {

    NOT_FOUNT(HttpStatus.NOT_FOUND,
            "COMMON400_1",
            "유저를 찾을 수 없습니다.");


    private final HttpStatus status;
    private final String code;
    private final String message;
}

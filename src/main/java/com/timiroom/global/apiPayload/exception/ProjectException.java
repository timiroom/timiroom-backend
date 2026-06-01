package com.timiroom.global.apiPayload.exception;

import com.timiroom.global.apiPayload.code.BaseErrorCode;
import com.timiroom.global.apiPayload.code.BaseSuccessCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class ProjectException extends RuntimeException{
    private final BaseErrorCode baseErrorCode;
}

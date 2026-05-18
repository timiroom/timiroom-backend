package com.timiroom.global.apiPayload.exception;

import com.fasterxml.jackson.databind.ser.Serializers.Base;
import com.timiroom.global.apiPayload.code.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class ProjectException extends RuntimeException {
    private final BaseErrorCode baseErrorCode;
}

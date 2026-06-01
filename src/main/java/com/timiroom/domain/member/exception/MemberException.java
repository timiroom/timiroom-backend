package com.timiroom.domain.member.exception;

import com.timiroom.global.apiPayload.code.BaseErrorCode;
import com.timiroom.global.apiPayload.exception.ProjectException;

public class MemberException extends ProjectException {
    public MemberException(BaseErrorCode baseErrorCode) {
        super(baseErrorCode);
    }
}

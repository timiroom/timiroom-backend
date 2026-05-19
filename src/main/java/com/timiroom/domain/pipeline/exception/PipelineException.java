package com.timiroom.domain.pipeline.exception;

import com.timiroom.global.apiPayload.code.BaseErrorCode;
import com.timiroom.global.apiPayload.exception.ProjectException;

public class PipelineException extends ProjectException {
    public PipelineException(BaseErrorCode baseErrorCode) {
        super(baseErrorCode);
    }
}

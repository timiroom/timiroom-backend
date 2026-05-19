package com.timiroom.domain.pipeline.exception.code;

import com.timiroom.global.apiPayload.code.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PipelineErrorCode implements BaseErrorCode {

    ;

    private HttpStatus status;
    private String code;
    private String message;
}

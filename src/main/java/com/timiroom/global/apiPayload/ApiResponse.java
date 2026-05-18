package com.timiroom.global.apiPayload;

import com.timiroom.global.apiPayload.code.BaseErrorCode;
import com.timiroom.global.apiPayload.code.BaseSuccessCode;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class ApiResponse<T> {

    private final Boolean isSuccess;

    private final String code;

    private final String message;

    private T result;


    public static <T> ApiResponse<T> onSuccess(BaseSuccessCode code, T result) {
        return new ApiResponse<T>(false, code.getCode(), code.getMessage(), result);
    }

    public static <T> ApiResponse<T> onFailure(BaseErrorCode code, T result){
        return new ApiResponse<T>(false, code.getCode(), code.getMessage(), result);
    }
}

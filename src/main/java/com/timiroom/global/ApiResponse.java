package com.timiroom.global;

import com.timiroom.global.apiPayload.code.BaseErrorCode;
import com.timiroom.global.apiPayload.code.BaseSuccessCode;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class ApiResponse<T> {
    private Boolean isSuccess;
    private String code;
    private String message;
    private T result;

    public static <T> ApiResponse<T> onSuccess(BaseSuccessCode code, T result){
        return new ApiResponse<T>(true, code.getCode(), code.getMessage(), result);
    }

    public static <T> ApiResponse<T> onFailure(BaseErrorCode code, T result){
        return new ApiResponse<T>(false, code.getCode(), code.getMessage(), result);
    }
}

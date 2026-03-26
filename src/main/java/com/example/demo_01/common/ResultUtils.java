package com.example.demo_01.common;

import com.example.demo_01.exception.ErrorCode;

public final class ResultUtils {

    private ResultUtils() {
    }

    public static <T> BaseResponse<T> success(T data) {
        return new BaseResponse<>(ErrorCode.SUCCESS.getCode(), data, ErrorCode.SUCCESS.getMessage());
    }

    public static BaseResponse<Void> error(ErrorCode errorCode) {
        return new BaseResponse<>(errorCode);
    }

    public static BaseResponse<Void> error(ErrorCode errorCode, String message) {
        return new BaseResponse<>(errorCode.getCode(), null, message);
    }

    public static BaseResponse<Void> error(int code, String message) {
        return new BaseResponse<>(code, null, message);
    }
}

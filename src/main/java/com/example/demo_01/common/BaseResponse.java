package com.example.demo_01.common;

import com.example.demo_01.exception.ErrorCode;

import java.io.Serializable;

public class BaseResponse<T> implements Serializable {

    private final int code;
    private final T data;
    private final String message;

    public BaseResponse(int code, T data, String message) {
        this.code = code;
        this.data = data;
        this.message = message;
    }

    public BaseResponse(int code, T data) {
        this(code, data, "");
    }

    public BaseResponse(ErrorCode errorCode) {
        this(errorCode.getCode(), null, errorCode.getMessage());
    }

    public int getCode() {
        return code;
    }

    public T getData() {
        return data;
    }

    public String getMessage() {
        return message;
    }
}

package com.example.demo_01.exception;

public class BusinessException extends RuntimeException {

    private final int code;
    private final ErrorCode errorCode;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
        this.errorCode = null;
    }

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
        this.errorCode = errorCode;
    }

    public int getCode() {
        return code;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}

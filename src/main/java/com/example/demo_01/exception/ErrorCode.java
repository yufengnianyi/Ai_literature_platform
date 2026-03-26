package com.example.demo_01.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

public enum ErrorCode {

    SUCCESS(0, HttpStatus.OK, "ok"),
    PARAMS_ERROR(40000, HttpStatus.BAD_REQUEST, "Request parameters are invalid"),
    NOT_LOGIN_ERROR(40100, HttpStatus.UNAUTHORIZED, "Login required"),
    NO_AUTH_ERROR(40101, HttpStatus.FORBIDDEN, "No permission"),
    NOT_FOUND_ERROR(40400, HttpStatus.NOT_FOUND, "Requested data was not found"),
    CONFLICT_ERROR(40900, HttpStatus.CONFLICT, "Resource conflict"),
    FORBIDDEN_ERROR(40300, HttpStatus.FORBIDDEN, "Access forbidden"),
    SYSTEM_ERROR(50000, HttpStatus.INTERNAL_SERVER_ERROR, "Internal system error"),
    OPERATION_ERROR(50001, HttpStatus.INTERNAL_SERVER_ERROR, "Operation failed");

    private final int code;
    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(int code, HttpStatus httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getMessage() {
        return message;
    }

    public static ErrorCode fromHttpStatus(HttpStatusCode httpStatus) {
        if (httpStatus == null) {
            return SYSTEM_ERROR;
        }
        int value = httpStatus.value();
        return switch (value) {
            case 400 -> PARAMS_ERROR;
            case 401 -> NOT_LOGIN_ERROR;
            case 403 -> NO_AUTH_ERROR;
            case 404 -> NOT_FOUND_ERROR;
            case 409 -> CONFLICT_ERROR;
            default -> httpStatus.is4xxClientError() ? OPERATION_ERROR : SYSTEM_ERROR;
        };
    }
}

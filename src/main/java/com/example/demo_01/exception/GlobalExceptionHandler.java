package com.example.demo_01.exception;

import com.example.demo_01.common.BaseResponse;
import com.example.demo_01.common.ResultUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<BaseResponse<Void>> businessExceptionHandler(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode() == null ? ErrorCode.OPERATION_ERROR : e.getErrorCode();
        log.warn("BusinessException: code={}, message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ResultUtils.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<BaseResponse<Void>> responseStatusExceptionHandler(ResponseStatusException e) {
        ErrorCode errorCode = ErrorCode.fromHttpStatus(e.getStatusCode());
        String message = e.getReason() == null || e.getReason().isBlank() ? errorCode.getMessage() : e.getReason();
        log.warn("ResponseStatusException: status={}, message={}", e.getStatusCode(), message);
        return ResponseEntity.status(e.getStatusCode())
                .body(ResultUtils.error(errorCode, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<BaseResponse<Void>> runtimeExceptionHandler(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(ErrorCode.SYSTEM_ERROR.getHttpStatus())
                .body(ResultUtils.error(ErrorCode.SYSTEM_ERROR));
    }
}

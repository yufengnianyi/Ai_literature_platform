package com.example.demo_01.exception;

import com.example.demo_01.common.BaseResponse;
import com.example.demo_01.common.ResultUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
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

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<BaseResponse<Void>> maxUploadSizeExceededExceptionHandler(MaxUploadSizeExceededException e) {
        log.warn("Upload rejected because multipart request exceeded the configured limit: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ResultUtils.error(ErrorCode.PARAMS_ERROR, "Uploaded files exceed the configured size limit"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<BaseResponse<Void>> runtimeExceptionHandler(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(ErrorCode.SYSTEM_ERROR.getHttpStatus())
                .body(ResultUtils.error(ErrorCode.SYSTEM_ERROR));
    }
}

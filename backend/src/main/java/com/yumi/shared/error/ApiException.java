package com.yumi.shared.error;

import java.util.List;

public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<ApiFieldError> fieldErrors;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), List.of());
    }

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of());
    }

    public ApiException(ErrorCode errorCode, String message, List<ApiFieldError> fieldErrors) {
        super(message);
        this.errorCode = errorCode;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<ApiFieldError> fieldErrors() {
        return fieldErrors;
    }
}

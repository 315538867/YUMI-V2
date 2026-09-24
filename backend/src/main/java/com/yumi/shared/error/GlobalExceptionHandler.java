package com.yumi.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

import com.yumi.shared.web.RequestIdFilter;

/**
 * 统一错误契约：所有异常出口都转换为 {code, message, fieldErrors, requestId}。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiEnvelope> apiException(ApiException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.errorCode().status())
                .body(ApiEnvelope.error(exception.errorCode(), exception.getMessage(),
                        exception.fieldErrors(), RequestIdFilter.currentId(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiEnvelope> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<ApiFieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiFieldError(error.getField(), messageOf(error)))
                .toList();
        return badValidation(fieldErrors, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiEnvelope> constraintViolation(ConstraintViolationException exception, HttpServletRequest request) {
        List<ApiFieldError> fieldErrors = exception.getConstraintViolations().stream()
                .map(violation -> new ApiFieldError(violation.getPropertyPath().toString(), violation.getMessage()))
                .toList();
        return badValidation(fieldErrors, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiEnvelope> unreadable(HttpMessageNotReadableException exception, HttpServletRequest request) {
        return badValidation(List.of(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiEnvelope> typeMismatch(MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        return badValidation(List.of(new ApiFieldError(exception.getName(), "请求参数类型不合法")), request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiEnvelope> missingHeader(MissingRequestHeaderException exception, HttpServletRequest request) {
        return badValidation(List.of(new ApiFieldError(exception.getHeaderName(), "缺少必需请求头")), request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiEnvelope> optimisticLock(ObjectOptimisticLockingFailureException exception,
                                            HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.CONFLICT_VERSION.status())
                .body(ApiEnvelope.error(ErrorCode.CONFLICT_VERSION, RequestIdFilter.currentId(request)));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ApiEnvelope> duplicateKey(DuplicateKeyException exception, HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.CONFLICT_DUPLICATE.status())
                .body(ApiEnvelope.error(ErrorCode.CONFLICT_DUPLICATE, RequestIdFilter.currentId(request)));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiEnvelope> noResource(NoResourceFoundException exception, HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.NOT_FOUND.status())
                .body(ApiEnvelope.error(ErrorCode.NOT_FOUND, RequestIdFilter.currentId(request)));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiEnvelope> unexpected(Exception exception, HttpServletRequest request) {
        var requestId = RequestIdFilter.currentId(request);
        log.error("未处理异常 requestId={}", requestId, exception);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(ApiEnvelope.error(ErrorCode.INTERNAL_ERROR, requestId));
    }

    private ResponseEntity<ApiEnvelope> badValidation(List<ApiFieldError> fieldErrors, HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.VALIDATION_INVALID.status())
                .body(ApiEnvelope.error(ErrorCode.VALIDATION_INVALID, fieldErrors, RequestIdFilter.currentId(request)));
    }

    private String messageOf(FieldError error) {
        return error.getDefaultMessage() != null ? error.getDefaultMessage() : "请求参数校验失败";
    }
}

package com.yumi.platform.http;

import com.yumi.platform.api.http.ApiError;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.http.HttpHeaders;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 平台统一错误映射。
 *
 * <p>规则来源：`docs/一期/05-跨模块接口与事务规则.md` §9、§11。所有业务异常转换为带稳定类型、
 * 稳定错误码与请求编号的响应；未预期异常只返回通用系统失败，不泄露堆栈、SQL 或内部类名。
 * 响应头回传 {@code X-Request-Id}，便于用户报障时定位。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final HttpRequestContextResolver contextResolver;

    public ApiExceptionHandler(HttpRequestContextResolver contextResolver) {
        this.contextResolver = contextResolver;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException exception, HttpServletRequest request) {
        ApiError error = new ApiError(
                exception.type(),
                exception.code(),
                exception.getMessage(),
                contextResolver.resolveRequestId(request),
                exception.fieldIssues());
        return response(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBeanValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<ApiError.FieldIssue> issues = exception.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new ApiError.FieldIssue(
                        fieldError.getField(),
                        fieldError.getDefaultMessage() == null ? "字段不合法" : fieldError.getDefaultMessage()))
                .toList();
        return response(ApiError.validation(
                "请求参数不合法", contextResolver.resolveRequestId(request), issues));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        return response(new ApiError(
                ApiErrorType.VALIDATION,
                "INVALID_REQUEST_BODY",
                "请求体格式不合法；金额与数量必须使用十进制字符串",
                contextResolver.resolveRequestId(request),
                List.of()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(
            MissingRequestHeaderException exception, HttpServletRequest request) {
        return response(new ApiError(
                ApiErrorType.VALIDATION,
                "MISSING_HEADER",
                "缺少必需请求头：" + exception.getHeaderName(),
                contextResolver.resolveRequestId(request),
                List.of()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(
            MissingServletRequestParameterException exception, HttpServletRequest request) {
        return response(new ApiError(
                ApiErrorType.VALIDATION,
                "MISSING_PARAMETER",
                "缺少必需请求参数：" + exception.getParameterName(),
                contextResolver.resolveRequestId(request),
                List.of()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        return response(new ApiError(
                ApiErrorType.VALIDATION,
                "INVALID_PARAMETER",
                "请求参数格式不合法：" + exception.getName(),
                contextResolver.resolveRequestId(request),
                List.of()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(
            IllegalArgumentException exception, HttpServletRequest request) {        return response(new ApiError(
                ApiErrorType.VALIDATION,
                "VALIDATION_FAILED",
                exception.getMessage() == null ? "请求参数不合法" : exception.getMessage(),
                contextResolver.resolveRequestId(request),
                List.of()));
    }

    @ExceptionHandler({
            org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class})
    public ResponseEntity<ApiError> handleNotFound(Exception exception, HttpServletRequest request) {
        return response(new ApiError(
                ApiErrorType.NOT_FOUND,
                "NOT_FOUND",
                "请求的资源不存在",
                contextResolver.resolveRequestId(request),
                List.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        String requestId = contextResolver.resolveRequestId(request);
        log.error("未预期的系统失败，requestId={}", requestId, exception);
        return response(new ApiError(
                ApiErrorType.SYSTEM_FAILURE,
                "SYSTEM_FAILURE",
                "服务暂时不可用，请稍后重试",
                requestId,
                List.of()));
    }

    private ResponseEntity<ApiError> response(ApiError error) {
        return ResponseEntity.status(error.type().httpStatus())
                .header(HttpHeaders.REQUEST_ID, error.requestId())
                .body(error);
    }
}

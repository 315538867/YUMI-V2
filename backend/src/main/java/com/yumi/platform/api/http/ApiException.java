package com.yumi.platform.api.http;

import java.util.List;
import java.util.Objects;

/**
 * 业务异常载体：由业务模块抛出，HTTP 适配层转换为 {@link ApiError}。
 *
 * <p>异常消息是面向用户的中文说明，不允许包含 SQL、类名或内部标识。
 */
public class ApiException extends RuntimeException {

    private final ApiErrorType type;

    private final String code;

    private final transient List<ApiError.FieldIssue> fieldIssues;

    public ApiException(ApiErrorType type, String code, String message) {
        this(type, code, message, List.of());
    }

    public ApiException(
            ApiErrorType type, String code, String message, List<ApiError.FieldIssue> fieldIssues) {
        super(message);
        this.type = Objects.requireNonNull(type, "错误类型不能为空");
        this.code = Objects.requireNonNull(code, "错误码不能为空");
        this.fieldIssues = List.copyOf(Objects.requireNonNull(fieldIssues, "字段问题列表不能为空"));
    }

    public ApiErrorType type() {
        return type;
    }

    public String code() {
        return code;
    }

    public List<ApiError.FieldIssue> fieldIssues() {
        return fieldIssues;
    }
}

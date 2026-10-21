package com.yumi.platform.api.http;

import java.util.List;
import java.util.Objects;

/**
 * 稳定错误响应体。
 *
 * <p>只包含客户端可安全展示的信息：稳定类型、稳定错误码、面向用户的中文消息、请求编号和
 * 字段级问题。禁止序列化堆栈、内部异常类名或数据库细节。
 *
 * @param type 稳定错误类型
 * @param code 稳定错误码，例如 {@code ROW_VERSION_CONFLICT}
 * @param message 面向用户的消息
 * @param requestId 请求编号，用于审计与问题定位
 * @param fieldIssues 字段级问题；非校验错误为空列表
 */
public record ApiError(
        ApiErrorType type,
        String code,
        String message,
        String requestId,
        List<FieldIssue> fieldIssues) {

    public ApiError {
        Objects.requireNonNull(type, "错误类型不能为空");
        Objects.requireNonNull(code, "错误码不能为空");
        Objects.requireNonNull(message, "错误消息不能为空");
        Objects.requireNonNull(fieldIssues, "字段问题列表不能为空");
        fieldIssues = List.copyOf(fieldIssues);
    }

    /** 字段级校验问题。 */
    public record FieldIssue(String field, String reason) {
        public FieldIssue {
            Objects.requireNonNull(field, "字段名不能为空");
            Objects.requireNonNull(reason, "字段问题原因不能为空");
        }
    }

    public static ApiError of(ApiErrorType type, String code, String message, String requestId) {
        return new ApiError(type, code, message, requestId, List.of());
    }

    public static ApiError validation(
            String message, String requestId, List<FieldIssue> fieldIssues) {
        return new ApiError(ApiErrorType.VALIDATION, "VALIDATION_FAILED", message, requestId, fieldIssues);
    }
}

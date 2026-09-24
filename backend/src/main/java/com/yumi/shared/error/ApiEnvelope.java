package com.yumi.shared.error;

import java.util.List;
import java.util.UUID;

/**
 * 统一响应信封（design.md §2）：成功 code=OK 且 data 为业务负载；失败 code 为稳定错误码且 data=null。
 * 五个键在成功与失败时始终齐全（data 例外值为 null，键不省略）。
 */
public record ApiEnvelope(String code, String message, List<ApiFieldError> fieldErrors,
                          String requestId, Object data) {

    public static ApiEnvelope ok(Object data, String requestId) {
        return new ApiEnvelope("OK", "", List.of(), requestId, data);
    }

    public static ApiEnvelope error(ErrorCode errorCode, String requestId) {
        return error(errorCode, List.of(), requestId);
    }

    public static ApiEnvelope error(ErrorCode errorCode, List<ApiFieldError> fieldErrors, String requestId) {
        return new ApiEnvelope(errorCode.name(), errorCode.defaultMessage(), fieldErrors, requestId, null);
    }

    public static ApiEnvelope error(ErrorCode errorCode, String message, List<ApiFieldError> fieldErrors,
                                    String requestId) {
        return new ApiEnvelope(errorCode.name(), message, fieldErrors, requestId, null);
    }

    static String newRequestId() {
        return UUID.randomUUID().toString();
    }
}

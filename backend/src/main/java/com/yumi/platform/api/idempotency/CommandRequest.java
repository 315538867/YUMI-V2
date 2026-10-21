package com.yumi.platform.api.idempotency;

import java.util.Objects;

/**
 * 正式命令的幂等身份。
 *
 * @param scopeKey 命令所有者范围，例如 {@code orders.trade.confirm}；同一幂等键在不同范围互不影响
 * @param idempotencyKey 客户端幂等键，同一用户操作重试时复用
 * @param requestFingerprint 规范化请求内容的 SHA-256；用于识别“同键不同输入”
 */
public record CommandRequest(String scopeKey, String idempotencyKey, String requestFingerprint) {

    public CommandRequest {
        requireText(scopeKey, "命令范围不能为空");
        requireText(idempotencyKey, "幂等键不能为空");
        if (requestFingerprint == null || !requestFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("请求指纹必须为 64 位小写十六进制");
        }
    }

    private static void requireText(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}

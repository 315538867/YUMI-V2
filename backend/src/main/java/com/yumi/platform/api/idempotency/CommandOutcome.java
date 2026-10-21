package com.yumi.platform.api.idempotency;

import java.util.Objects;

/**
 * 命令成功结果引用：只保存结果类型与稳定业务标识，重试时原样复用。
 *
 * @param resultType 结果类型稳定代码，例如 {@code ORDER}
 * @param resultBusinessId 结果稳定业务标识
 */
public record CommandOutcome(String resultType, String resultBusinessId) {

    public CommandOutcome {
        Objects.requireNonNull(resultType, "结果类型不能为空");
        Objects.requireNonNull(resultBusinessId, "结果业务标识不能为空");
        if (resultType.isBlank() || resultBusinessId.isBlank()) {
            throw new IllegalArgumentException("结果类型与业务标识不能为空白");
        }
    }
}

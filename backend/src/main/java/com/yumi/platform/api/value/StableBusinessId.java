package com.yumi.platform.api.value;

import java.util.Objects;

/**
 * 稳定业务标识，对应 {@code VARCHAR(64)}。
 *
 * <p>创建后不可修改、不可复用，可跨模块保存；跨模块禁止暴露数据库内部主键。
 */
public record StableBusinessId(String value) {

    public static final int MAX_LENGTH = 64;

    public StableBusinessId {
        Objects.requireNonNull(value, "稳定业务标识不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException("稳定业务标识不能为空白");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "稳定业务标识长度不能超过 " + MAX_LENGTH + "：" + value);
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException("稳定业务标识不能包含首尾空白：" + value);
        }
    }

    public static StableBusinessId of(String value) {
        return new StableBusinessId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}

package com.yumi.platform.api.value;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 内容指纹：规范化输入的 SHA-256，对应 {@code CHAR(64)} 小写十六进制。
 *
 * <p>用于判断当前定义是否需要新快照、以及来源批次是否被完整消费。
 */
public record ContentFingerprint(String value) {

    private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{64}");

    public ContentFingerprint {
        Objects.requireNonNull(value, "内容指纹不能为空");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("内容指纹必须为 64 位小写十六进制：" + value);
        }
    }

    public static ContentFingerprint of(String value) {
        return new ContentFingerprint(value);
    }

    @Override
    public String toString() {
        return value;
    }
}

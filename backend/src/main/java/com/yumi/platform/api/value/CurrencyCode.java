package com.yumi.platform.api.value;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 币种代码（ISO 4217 三位大写字母）。
 *
 * <p>一期只使用人民币 {@link #CNY}，不做币种兑换；金额仍携带币种，用于校验和未来扩展。
 */
public record CurrencyCode(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Z]{3}");

    public static final CurrencyCode CNY = new CurrencyCode("CNY");

    public CurrencyCode {
        Objects.requireNonNull(value, "币种不能为空");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("币种必须为 3 位大写字母代码：" + value);
        }
    }

    public static CurrencyCode of(String value) {
        return new CurrencyCode(value);
    }

    @Override
    public String toString() {
        return value;
    }
}

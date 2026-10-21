package com.yumi.platform.api.http.json;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * HTTP 十进制字符串契约的共享校验。
 *
 * <p>只接受普通十进制写法（可带负号的整数或小数），不接受科学计数法、NaN、Infinity 或空白；
 * 金额、重量、比例在线上统一以 JSON 字符串传输，避免二进制浮点与前端数字精度丢失。
 */
public final class DecimalText {

    /** 允许的十进制字面量：{@code -?digits[.digits]}。 */
    public static final String PATTERN = "-?(0|[1-9][0-9]*)(\\.[0-9]+)?";

    private static final Pattern DECIMAL = Pattern.compile(PATTERN);

    private DecimalText() {
    }

    public static String requireDecimalText(String raw, String field) {
        Objects.requireNonNull(raw, field + " 不能为 null");
        if (!DECIMAL.matcher(raw).matches()) {
            throw new IllegalArgumentException(
                    field + " 必须是十进制字符串（不接受科学计数法）：" + raw);
        }
        return raw;
    }

    public static BigDecimal parse(String raw, String field) {
        return new BigDecimal(requireDecimalText(raw, field));
    }

    public static String format(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }
}

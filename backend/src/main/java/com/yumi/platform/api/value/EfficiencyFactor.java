package com.yumi.platform.api.value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 小组效率因子，对应 {@code DECIMAL(12,8)}，必须严格大于 1。
 *
 * <p>效率只影响负载折算，不影响计件工资参考金额。
 */
public record EfficiencyFactor(BigDecimal value) {

    public static final int SCALE = 8;

    public static final int INTEGER_DIGITS = 4;

    public EfficiencyFactor {
        Objects.requireNonNull(value, "效率因子不能为空");
        value = value.setScale(SCALE, RoundingMode.HALF_UP);
        if (value.compareTo(BigDecimal.ONE) <= 0) {
            throw new IllegalArgumentException("效率因子必须大于 1：" + value.toPlainString());
        }
        if (value.precision() - value.scale() > INTEGER_DIGITS) {
            throw new IllegalArgumentException("效率因子超出 DECIMAL(12,8) 范围：" + value.toPlainString());
        }
    }

    public static EfficiencyFactor of(BigDecimal value) {
        return new EfficiencyFactor(value);
    }

    public static EfficiencyFactor of(String value) {
        return new EfficiencyFactor(new BigDecimal(value));
    }
}

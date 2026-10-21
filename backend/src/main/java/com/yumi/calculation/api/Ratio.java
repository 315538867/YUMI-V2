package com.yumi.calculation.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** 比例结果，对应 {@code DECIMAL(12,8)}；分母为零时调用方必须返回空而不是本类型。 */
public record Ratio(BigDecimal value) {

    public static final int SCALE = 8;

    public Ratio {
        Objects.requireNonNull(value, "比例不能为空");
        value = value.setScale(SCALE, RoundingMode.HALF_UP);
        if (value.precision() - value.scale() > 4) {
            throw new IllegalArgumentException("比例超出 DECIMAL(12,8) 范围：" + value.toPlainString());
        }
    }

    public static Ratio of(BigDecimal value) {
        return new Ratio(value);
    }
}

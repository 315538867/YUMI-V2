package com.yumi.platform.api.value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 损耗率，对应 {@code DECIMAL(12,8)}，取值范围 {@code [0,1)}。
 *
 * <p>按小数存储，例如 {@code 0.05} 表示 5%。
 */
public record LossRate(BigDecimal value) {

    public static final int SCALE = 8;

    public static final int INTEGER_DIGITS = 4;

    public static final LossRate ZERO = new LossRate(BigDecimal.ZERO);

    public LossRate {
        Objects.requireNonNull(value, "损耗率不能为空");
        value = value.setScale(SCALE, RoundingMode.HALF_UP);
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("损耗率必须在 [0,1) 范围内：" + value.toPlainString());
        }
        if (value.precision() - value.scale() > INTEGER_DIGITS) {
            throw new IllegalArgumentException("损耗率超出 DECIMAL(12,8) 范围：" + value.toPlainString());
        }
    }

    public static LossRate of(BigDecimal value) {
        return new LossRate(value);
    }

    public static LossRate of(String value) {
        return new LossRate(new BigDecimal(value));
    }

    /** 计价用量系数：输入量 × (1 + 损耗率)。 */
    public BigDecimal multiplier() {
        return BigDecimal.ONE.add(value);
    }
}

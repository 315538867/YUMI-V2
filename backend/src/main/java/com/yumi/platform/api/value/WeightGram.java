package com.yumi.platform.api.value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 重量，单位固定为克，对应 {@code DECIMAL(19,4)}。
 *
 * <p>单位由类型固定，接口层不再重复传递单位字符串；展示层可格式化。
 */
public record WeightGram(BigDecimal value) {

    public static final int SCALE = 4;

    public static final int INTEGER_DIGITS = 15;

    public static final String UNIT = "g";

    public static final WeightGram ZERO = new WeightGram(BigDecimal.ZERO);

    public WeightGram {
        Objects.requireNonNull(value, "重量不能为空");
        value = value.setScale(SCALE, RoundingMode.HALF_UP);
        if (value.signum() < 0) {
            throw new IllegalArgumentException("重量不允许为负：" + value.toPlainString());
        }
        if (value.precision() - value.scale() > INTEGER_DIGITS) {
            throw new IllegalArgumentException("重量超出 DECIMAL(19,4) 范围：" + value.toPlainString());
        }
    }

    public static WeightGram ofGrams(BigDecimal value) {
        return new WeightGram(value);
    }

    public static WeightGram ofGrams(String value) {
        return new WeightGram(new BigDecimal(value));
    }

    public String unit() {
        return UNIT;
    }

    public WeightGram plus(WeightGram other) {
        Objects.requireNonNull(other, "加数不能为空");
        return new WeightGram(value.add(other.value));
    }

    public WeightGram multiply(BigDecimal factor) {
        Objects.requireNonNull(factor, "乘数不能为空");
        if (factor.signum() < 0) {
            throw new IllegalArgumentException("重量乘数不允许为负：" + factor.toPlainString());
        }
        return new WeightGram(value.multiply(factor));
    }
}

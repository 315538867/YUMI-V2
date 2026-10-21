package com.yumi.platform.api.value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 有符号成本差额：仅用于更正、冲销等场景，对应 {@code DECIMAL(19,4)}。
 *
 * <p>与 {@link CostAmount} 是不同类型，调用方不能依赖约定区分正负语义。
 */
public record SignedCostAmount(BigDecimal value, CurrencyCode currency) implements MoneyAmount {

    public static final int SCALE = CostAmount.SCALE;

    public static final int INTEGER_DIGITS = CostAmount.INTEGER_DIGITS;

    public static final SignedCostAmount ZERO = new SignedCostAmount(BigDecimal.ZERO, CurrencyCode.CNY);

    public SignedCostAmount {
        Objects.requireNonNull(value, "金额不能为空");
        Objects.requireNonNull(currency, "币种不能为空");
        value = value.setScale(SCALE, RoundingMode.HALF_UP);
        if (value.precision() - value.scale() > INTEGER_DIGITS) {
            throw new IllegalArgumentException("成本差额超出 DECIMAL(19,4) 范围：" + value.toPlainString());
        }
    }

    public static SignedCostAmount of(BigDecimal value) {
        return new SignedCostAmount(value, CurrencyCode.CNY);
    }

    public static SignedCostAmount of(BigDecimal value, CurrencyCode currency) {
        return new SignedCostAmount(value, currency);
    }

    public static SignedCostAmount of(CostAmount amount) {
        Objects.requireNonNull(amount, "金额不能为空");
        return new SignedCostAmount(amount.value(), amount.currency());
    }

    public SignedCostAmount negate() {
        return new SignedCostAmount(value.negate(), currency);
    }
}

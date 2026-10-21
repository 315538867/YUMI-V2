package com.yumi.platform.api.value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 成本金额：预算单价、单件成本、成本台账金额，对应 {@code DECIMAL(19,4)}。
 *
 * <p>默认非负；按 {@code HALF_UP} 舍入到 4 位；整数部分最多 15 位。有符号差额请使用
 * {@link SignedCostAmount}，不能借用本类型表达负数。
 */
public record CostAmount(BigDecimal value, CurrencyCode currency) implements MoneyAmount {

    /** 成本精度：4 位小数。 */
    public static final int SCALE = 4;

    /** DECIMAL(19,4) 的整数位数上限。 */
    public static final int INTEGER_DIGITS = 15;

    public static final CostAmount ZERO = new CostAmount(BigDecimal.ZERO, CurrencyCode.CNY);

    public CostAmount {
        Objects.requireNonNull(value, "金额不能为空");
        Objects.requireNonNull(currency, "币种不能为空");
        value = value.setScale(SCALE, RoundingMode.HALF_UP);
        if (value.signum() < 0) {
            throw new IllegalArgumentException("成本金额不允许为负，请使用 SignedCostAmount：" + value.toPlainString());
        }
        if (value.precision() - value.scale() > INTEGER_DIGITS) {
            throw new IllegalArgumentException("成本金额超出 DECIMAL(19,4) 范围：" + value.toPlainString());
        }
    }

    public static CostAmount of(BigDecimal value, CurrencyCode currency) {
        return new CostAmount(value, currency);
    }

    public static CostAmount of(String value, CurrencyCode currency) {
        return new CostAmount(new BigDecimal(value), currency);
    }

    public CostAmount plus(CostAmount other) {
        Objects.requireNonNull(other, "加数不能为空");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "币种不一致，不能直接相加：" + currency + " 与 " + other.currency);
        }
        return new CostAmount(value.add(other.value), currency);
    }
}

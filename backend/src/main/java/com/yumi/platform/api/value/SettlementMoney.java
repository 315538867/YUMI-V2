package com.yumi.platform.api.value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 结算金额：应收、收退款、工资结算及现金，对应 {@code DECIMAL(19,2)}。
 *
 * <p>默认非负；按 {@code HALF_UP} 舍入到分；整数部分最多 17 位。不得把 4 位成本精度
 * 直接带入本类型。有符号差额请使用 {@link SignedSettlementAmount}。
 */
public record SettlementMoney(BigDecimal value, CurrencyCode currency) implements MoneyAmount {

    public static final int SCALE = 2;

    public static final int INTEGER_DIGITS = 17;

    public static final SettlementMoney ZERO = new SettlementMoney(BigDecimal.ZERO, CurrencyCode.CNY);

    public SettlementMoney {
        Objects.requireNonNull(value, "金额不能为空");
        Objects.requireNonNull(currency, "币种不能为空");
        value = value.setScale(SCALE, RoundingMode.HALF_UP);
        if (value.signum() < 0) {
            throw new IllegalArgumentException("结算金额不允许为负，请使用 SignedSettlementAmount：" + value.toPlainString());
        }
        if (value.precision() - value.scale() > INTEGER_DIGITS) {
            throw new IllegalArgumentException("结算金额超出 DECIMAL(19,2) 范围：" + value.toPlainString());
        }
    }

    public static SettlementMoney of(BigDecimal value, CurrencyCode currency) {
        return new SettlementMoney(value, currency);
    }

    public static SettlementMoney of(String value, CurrencyCode currency) {
        return new SettlementMoney(new BigDecimal(value), currency);
    }

    public SettlementMoney plus(SettlementMoney other) {
        Objects.requireNonNull(other, "加数不能为空");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "币种不一致，不能直接相加：" + currency + " 与 " + other.currency);
        }
        return new SettlementMoney(value.add(other.value), currency);
    }
}

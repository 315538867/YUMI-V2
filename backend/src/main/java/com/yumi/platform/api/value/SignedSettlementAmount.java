package com.yumi.platform.api.value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** 有符号结算差额：用于调整、更正、冲销，对应 {@code DECIMAL(19,2)}。 */
public record SignedSettlementAmount(BigDecimal value, CurrencyCode currency) implements MoneyAmount {

    public static final int SCALE = SettlementMoney.SCALE;

    public static final int INTEGER_DIGITS = SettlementMoney.INTEGER_DIGITS;

    public static final SignedSettlementAmount ZERO =
            new SignedSettlementAmount(BigDecimal.ZERO, CurrencyCode.CNY);

    public SignedSettlementAmount {
        Objects.requireNonNull(value, "金额不能为空");
        Objects.requireNonNull(currency, "币种不能为空");
        value = value.setScale(SCALE, RoundingMode.HALF_UP);
        if (value.precision() - value.scale() > INTEGER_DIGITS) {
            throw new IllegalArgumentException("结算差额超出 DECIMAL(19,2) 范围：" + value.toPlainString());
        }
    }

    public static SignedSettlementAmount of(BigDecimal value) {
        return new SignedSettlementAmount(value, CurrencyCode.CNY);
    }

    public static SignedSettlementAmount of(BigDecimal value, CurrencyCode currency) {
        return new SignedSettlementAmount(value, currency);
    }

    public static SignedSettlementAmount of(SettlementMoney amount) {
        Objects.requireNonNull(amount, "金额不能为空");
        return new SignedSettlementAmount(amount.value(), amount.currency());
    }

    public SignedSettlementAmount negate() {
        return new SignedSettlementAmount(value.negate(), currency);
    }
}

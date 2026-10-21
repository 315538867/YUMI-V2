package com.yumi.calculation;

import com.yumi.calculation.api.CostAmountCalculator;
import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.Quantity;
import com.yumi.platform.api.value.SettlementMoney;
import java.math.BigDecimal;

/**
 * 金额乘法实现：先乘后舍入，在对应的金额域边界按 {@code HALF_UP} 取精度。
 *
 * <p>规则来源：`docs/一期/07-计算中心模块.md` §4.6—§4.8。禁止先把单价舍入再乘数量。
 */
public final class CostAmountCalculatorImpl implements CostAmountCalculator {

    @Override
    public SettlementMoney multiply(Quantity quantity, SettlementMoney unitAmount) {
        if (quantity == null || unitAmount == null) {
            throw new IllegalArgumentException("数量与单件结算金额不能为空");
        }
        BigDecimal raw = unitAmount.value().multiply(BigDecimal.valueOf(quantity.value()));
        return SettlementMoney.of(raw, unitAmount.currency());
    }

    @Override
    public SettlementMoney multiplyToSettlement(Quantity quantity, CostAmount unitCost) {
        if (quantity == null || unitCost == null) {
            throw new IllegalArgumentException("数量与单件人工成本不能为空");
        }
        BigDecimal raw = unitCost.value().multiply(BigDecimal.valueOf(quantity.value()));
        return SettlementMoney.of(raw, unitCost.currency());
    }

    @Override
    public CostAmount multiplyCost(Quantity quantity, CostAmount unitCost) {
        if (quantity == null || unitCost == null) {
            throw new IllegalArgumentException("数量与单件成本不能为空");
        }
        BigDecimal raw = unitCost.value().multiply(BigDecimal.valueOf(quantity.value()));
        return CostAmount.of(raw, unitCost.currency());
    }

    /** 便捷入口。 */
    public static CostAmountCalculator create() {
        return new CostAmountCalculatorImpl();
    }
}

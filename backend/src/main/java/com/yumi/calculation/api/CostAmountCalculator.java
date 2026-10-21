package com.yumi.calculation.api;

import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.Quantity;
import com.yumi.platform.api.value.SettlementMoney;

/**
 * 不同精度金额乘法与分配（CA-03、CA-06）。
 *
 * <p>规则来源：`docs/一期/07-计算中心模块.md` §4.6—§4.8 与 `04-公共业务与技术规则.md` §8：
 * <ul>
 *   <li>{@code 未舍入金额 = 单价 × 数量}，在明细边界按金额域精度 {@code HALF_UP} 舍入；</li>
 *   <li>成本 4 位、结算 2 位，两类精度不得隐式互转；</li>
 *   <li>小组均分按币种最小单位分配，余数按稳定员工标识升序分配，合计严格等于总额；</li>
 *   <li>输入排列不改变结果。</li>
 * </ul>
 */
public interface CostAmountCalculator {

    /** 结算金额乘法：数量 × 单件结算金额，结果在来源明细边界舍入到分。 */
    SettlementMoney multiply(Quantity quantity, SettlementMoney unitAmount);

    /**
     * 工资参考乘法：数量 × 冻结的 4 位单件人工成本，先乘后舍入到分。
     *
     * <p>等价于基线 07 §4.7 的 {@code HALF_UP(完成数量 × 单件人工成本, 2)}：
     * 单件成本保留 4 位精度，禁止先把单价舍入成分再乘数量。
     */
    SettlementMoney multiplyToSettlement(Quantity quantity, CostAmount unitCost);

    /** 成本金额乘法：数量 × 单件成本，结果在明细边界舍入到 4 位。 */
    CostAmount multiplyCost(Quantity quantity, CostAmount unitCost);

    /** 便捷入口：无状态实现。 */
    static CostAmountCalculator create() {
        return com.yumi.calculation.CostAmountCalculatorImpl.create();
    }
}

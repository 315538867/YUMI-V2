package com.yumi.calculation.api;

import com.yumi.platform.api.value.CostAmount;

/**
 * 成本行金额边界：明细在来源边界按成本精度舍入，合计只累加已舍入明细。
 *
 * <p>规则来源：`docs/一期/07-计算中心模块.md` §4.6—§4.8：产品成本与材料成本为 4 位小数、
 * 工资与现金为 2 位小数；两者不得隐式互转。
 */
public final class CostAmounts {

    private CostAmounts() {
    }

    /** 4 位成本明细：统一入口，避免各调用方自行设定舍入方式。 */
    public static CostAmount line(CostAmount cost) {
        if (cost == null) {
            throw new IllegalArgumentException("成本明细不能为空");
        }
        return CostAmount.of(cost.value(), cost.currency());
    }
}

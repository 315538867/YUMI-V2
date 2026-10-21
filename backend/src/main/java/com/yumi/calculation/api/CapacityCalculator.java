package com.yumi.calculation.api;

import java.util.Optional;

/**
 * 商品单日最大产能计算（纯函数）。
 *
 * <p>规则来源：`docs/一期/07-计算中心模块.md` §4.1：{@code 单日最大产能 = 模具数量 × 每日批次}。
 * 结果只是新制上限，不含效率与人数。
 */
public interface CapacityCalculator {

    static DailyCapacityResult calculate(DailyCapacityInput input) {
        return com.yumi.calculation.CapacityCalculatorImpl.calculate(input);
    }
}

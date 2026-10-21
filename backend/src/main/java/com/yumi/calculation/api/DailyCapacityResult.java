package com.yumi.calculation.api;

import java.util.Objects;

/**
 * 商品单日最大产能结果。
 *
 * @param maxDailyUnits 单日最大新制件数
 * @param trace 公式追溯标识
 */
public record DailyCapacityResult(int maxDailyUnits, FormulaTrace trace) {

    public DailyCapacityResult {
        Objects.requireNonNull(trace, "公式追溯标识不能为空");
        if (maxDailyUnits < 0) {
            throw new IllegalArgumentException("产能不允许为负：" + maxDailyUnits);
        }
    }
}

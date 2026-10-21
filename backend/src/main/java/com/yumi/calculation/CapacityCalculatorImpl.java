package com.yumi.calculation;

import com.yumi.calculation.api.DailyCapacityInput;
import com.yumi.calculation.api.DailyCapacityResult;
import com.yumi.calculation.api.FormulaFingerprints;
import com.yumi.calculation.api.FormulaTrace;
import com.yumi.calculation.api.FormulaVersions;

/**
 * 商品单日最大产能实现：{@code 模具数量 × 模具每日生产批次}。
 *
 * <p>乘法使用 {@code Math.multiplyExact}，溢出明确报错而不是静默截断。
 */
public final class CapacityCalculatorImpl {

    private CapacityCalculatorImpl() {
    }

    public static DailyCapacityResult calculate(DailyCapacityInput input) {
        if (input == null) {
            throw new IllegalArgumentException("产能输入不能为空");
        }
        int maxDailyUnits;
        try {
            maxDailyUnits = Math.multiplyExact(input.moldCount(), input.batchesPerMoldPerDay());
        } catch (ArithmeticException overflow) {
            throw new ArithmeticException("单日产能超出整数范围，请检查模具数量或批次");
        }
        return new DailyCapacityResult(
                maxDailyUnits,
                new FormulaTrace(
                        FormulaVersions.DAILY_CAPACITY,
                        FormulaVersions.RULE_VERSION,
                        FormulaVersions.ROUNDING_SECONDS_CEILING,
                        FormulaFingerprints.of(
                                "moldCount", input.moldCount(),
                                "batchesPerMoldPerDay", input.batchesPerMoldPerDay())));
    }
}

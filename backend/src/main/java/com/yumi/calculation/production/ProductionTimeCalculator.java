package com.yumi.calculation.production;

import com.yumi.calculation.DecimalPolicy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 生产时间计算（阶段五 5.3）：标准分钟、正常工时、正常产能与返工工时。
 *
 * <p>公式（`production-module-design.md` §4.4）：
 * <pre>
 * 制作正常产能分钟   = workday_hours × 60 × making_effective_hour_rate
 * 包装/缝边正常产能  = workday_hours × 60
 * 正常标准分钟       = planned_quantity × standard_minutes
 * 正常工时           = 正常标准分钟 ÷ 60
 * </pre>
 * 返工不占正常产能、不计正常工时，也不构造虚构正常工时；标准分钟一律来自冻结快照，不由工作日分钟或产能倒推。
 * 容量超出只产生提示，不改变产品 `dailyMaxCapacity` 的正常硬约束。
 *
 * <p>本类只接收不可变数值并返回结果，不访问数据库、业务实体、当前时间或登录上下文。
 */
public final class ProductionTimeCalculator {

    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);
    private static final int HOUR_SCALE = 4;

    private ProductionTimeCalculator() {
    }

    /** 正常任务：按工序口径计算正常标准分钟、正常工时与正常产能占用。 */
    public static ProductionTimeResult calculateNormal(int plannedQuantity, int standardMinutes,
                                                       ProductionWorkType workType,
                                                       BigDecimal makingEffectiveRate,
                                                       BigDecimal workdayHours) {
        requirePositiveQuantity(plannedQuantity);
        requireNonNegativeMinutes(standardMinutes);
        if (workType == null) {
            throw new IllegalArgumentException("缺少工序口径");
        }
        long normalMinutes = (long) plannedQuantity * standardMinutes;
        long capacityMinutes = capacityMinutes(workType, makingEffectiveRate, workdayHours);
        return new ProductionTimeResult(plannedQuantity, standardMinutes, normalMinutes, 0L,
                hours(normalMinutes), capacityMinutes, notice(normalMinutes, capacityMinutes));
    }

    /**
     * 返工任务：正常标准分钟、正常工时与正常产能占用恒为 0，只返回返工标准分钟。
     * 单件标准分钟仍是该工序冻结值，便于追溯。
     */
    public static ProductionTimeResult calculateRework(int plannedQuantity, int standardMinutes) {
        requirePositiveQuantity(plannedQuantity);
        requireNonNegativeMinutes(standardMinutes);
        return new ProductionTimeResult(plannedQuantity, standardMinutes, 0L,
                (long) plannedQuantity * standardMinutes, BigDecimal.ZERO.setScale(HOUR_SCALE), 0L, null);
    }

    /** 该工序当日正常产能分钟；制作套用制品有效工时率，包装与缝边剪袋用完整工作日分钟。 */
    public static long capacityMinutes(ProductionWorkType workType, BigDecimal makingEffectiveRate,
                                       BigDecimal workdayHours) {
        BigDecimal workday = workdayHours == null ? BigDecimal.ZERO : workdayHours;
        BigDecimal minutes = workday.multiply(MINUTES_PER_HOUR);
        if (workType == ProductionWorkType.MAKING) {
            BigDecimal rate = makingEffectiveRate == null ? BigDecimal.ZERO : makingEffectiveRate;
            minutes = minutes.multiply(rate);
        }
        return minutes.setScale(0, RoundingMode.FLOOR).longValue();
    }

    private static BigDecimal hours(long minutes) {
        return BigDecimal.valueOf(minutes).divide(MINUTES_PER_HOUR, HOUR_SCALE, RoundingMode.HALF_UP);
    }

    /** 超出产能才提示；不足（仍有剩余产能）不产生提示。 */
    private static String notice(long normalMinutes, long capacityMinutes) {
        if (normalMinutes <= capacityMinutes) {
            return null;
        }
        return "超出当日正常产能 " + (normalMinutes - capacityMinutes) + " 分钟";
    }

    private static void requirePositiveQuantity(int plannedQuantity) {
        if (plannedQuantity < 1) {
            throw new IllegalArgumentException("计划数量必须大于 0");
        }
    }

    private static void requireNonNegativeMinutes(int standardMinutes) {
        if (standardMinutes < 0) {
            throw new IllegalArgumentException("标准分钟不得为负");
        }
    }

    /** 与 {@link DecimalPolicy} 的金额精度保持一致，供调用方断言。 */
    static int hourScale() {
        return HOUR_SCALE;
    }
}

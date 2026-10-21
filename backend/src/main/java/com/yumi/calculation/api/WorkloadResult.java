package com.yumi.calculation.api;

import com.yumi.platform.api.value.DurationSeconds;

/**
 * 工作量评估结果。
 *
 * <p>规则来源：`docs/一期/07-计算中心模块.md` §4.3—§4.5。剩余秒数按
 * {@code max(0, 每日有效 − 已占用)} 计算，但必须同时返回原始占用与超出信号，
 * 不能用 {@code max(0)} 掩盖超载。
 *
 * @param requiredSeconds 本次任务标准秒数（小组为整体经过时间）
 * @param occupiedSecondsBefore 比较前已占用秒数
 * @param dailyEffectiveSeconds 每日有效秒数
 * @param remainingSecondsBefore 比较前剩余秒数（不小于 0）
 * @param exceeded 已占用是否超过每日有效时间
 * @param comparison 候选任务与剩余秒数的比较结果
 * @param trace 公式追溯标识
 */
public record WorkloadResult(
        DurationSeconds requiredSeconds,
        DurationSeconds occupiedSecondsBefore,
        DurationSeconds dailyEffectiveSeconds,
        DurationSeconds remainingSecondsBefore,
        boolean exceeded,
        WorkloadComparison comparison,
        FormulaTrace trace) {
}

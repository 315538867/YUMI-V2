package com.yumi.calculation.production;

import java.math.BigDecimal;

/**
 * 生产时间计算结果（阶段五 5.3）：任务创建、任务详情、批量核验响应与工作台共用同一份结果。
 *
 * <p>{@code normalMinutes}/{@code normalHours}/{@code capacityMinutes} 只描述**正常生产**占用；
 * {@code REWORK} 的正常占用恒为 0，返工工时只出现在 {@code reworkMinutes}，不得计入正常工时与正常产能。
 *
 * @param plannedQuantity 计划数量
 * @param standardMinutes 单件标准分钟（创建时冻结）
 * @param normalMinutes   正常标准分钟 = 计划数量 × 单件标准分钟；返工为 0
 * @param reworkMinutes   返工标准分钟 = 计划数量 × 单件标准分钟；正常为 0
 * @param normalHours     正常工时（小时，scale4）= 正常标准分钟 ÷ 60；返工为 0
 * @param capacityMinutes 该工序当日正常产能分钟；返工为 0
 * @param capacityNotice  超出当日正常产能的提示文本；未超出时为 null（不足不产生提示）
 */
public record ProductionTimeResult(
        int plannedQuantity,
        int standardMinutes,
        long normalMinutes,
        long reworkMinutes,
        BigDecimal normalHours,
        long capacityMinutes,
        String capacityNotice) {
}

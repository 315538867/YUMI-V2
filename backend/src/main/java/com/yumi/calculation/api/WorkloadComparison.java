package com.yumi.calculation.api;

/** 工作量比较结果：候选任务秒数与创建前剩余有效秒数比较。 */
public enum WorkloadComparison {
    UNDER_STANDARD,
    WITHIN_STANDARD,
    OVER_STANDARD,
    /** 输入缺失、主体不可用或效率标准缺失。 */
    UNAVAILABLE
}

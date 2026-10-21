package com.yumi.calculation.api;

/**
 * 任务类型（与生产模块的任务类型一致）。
 *
 * <p>只有新制、超额与修复任务按小组效率折算；其他任务的录入标准分钟已经是整体经过时间。
 */
public enum TaskKind {
    NORMAL_PRODUCTION,
    EXTRA_PRODUCTION,
    REPAIR,
    OTHER_TASK;

    /** 是否按小组效率折算整体负载。 */
    public boolean appliesEfficiency() {
        return this == NORMAL_PRODUCTION || this == EXTRA_PRODUCTION || this == REPAIR;
    }

    /** 是否使用任务明细的商品工序标准分钟。 */
    public boolean usesItemStandards() {
        return appliesEfficiency();
    }
}

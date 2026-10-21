package com.yumi.production.api;

/** 作业任务类型（基线 11 §6.1）。 */
public enum TaskType {
    NORMAL_PRODUCTION,
    EXTRA_PRODUCTION,
    REPAIR,
    OTHER_TASK;

    /** 是否需要生产来源与任务明细。 */
    public boolean bindsProductionSource() {
        return this != OTHER_TASK;
    }

    /** 是否占用唯一日槽位：返工不设唯一槽位。 */
    public boolean occupiesUniqueSlot() {
        return this != REPAIR;
    }

    /** 是否依赖同日有效正常生产任务。 */
    public boolean requiresNormalProduction() {
        return this == EXTRA_PRODUCTION || this == REPAIR;
    }

    /** 是否允许超出每日有效时间而不需覆盖权限。 */
    public boolean allowsOverStandard() {
        return this == EXTRA_PRODUCTION;
    }

    /** 是否占用商品新制产能（仅 NEW_PRODUCT + MAKING 明细占用，见 DailyLoad）。 */
    public boolean isProductionOrRepair() {
        return bindsProductionSource();
    }
}

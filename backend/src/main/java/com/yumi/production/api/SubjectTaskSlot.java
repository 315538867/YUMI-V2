package com.yumi.production.api;

/** 员工日任务槽位类型。 */
public enum SubjectTaskSlot {
    NORMAL_PRODUCTION,
    EXTRA_PRODUCTION,
    OTHER_TASK,
    REPAIR;

    /** 是否唯一：返工可以多个。 */
    public boolean uniquePerDay() {
        return this != REPAIR;
    }
}

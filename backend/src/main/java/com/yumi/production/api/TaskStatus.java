package com.yumi.production.api;

/** 任务状态：正式创建成功直接进入安排状态，不提供草稿。 */
public enum TaskStatus {
    ARRANGED,
    PARTIALLY_VERIFIED,
    VERIFIED,
    CANCELLED
}

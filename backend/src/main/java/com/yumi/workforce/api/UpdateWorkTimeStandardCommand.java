package com.yumi.workforce.api;

/** 修改全局每日有效分钟标准；有活动引用时拒绝。 */
public record UpdateWorkTimeStandardCommand(int effectiveMinutes, long expectedRowVersion, String reason) {
}

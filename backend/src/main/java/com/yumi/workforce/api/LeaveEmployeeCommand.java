package com.yumi.workforce.api;

import java.time.LocalDate;

/** 离职命令：存在活动引用时拒绝。 */
public record LeaveEmployeeCommand(
        String employeeBusinessId, LocalDate effectiveDate, String reason, long expectedRowVersion) {
}

package com.yumi.workforce.api;

import java.time.LocalDate;

/** 返聘命令：沿用原员工身份，追加就业事实。 */
public record RehireEmployeeCommand(
        String employeeBusinessId, LocalDate effectiveDate, long expectedRowVersion) {
}

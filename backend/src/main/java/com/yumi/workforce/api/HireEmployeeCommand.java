package com.yumi.workforce.api;

import java.time.LocalDate;

/** 入职命令：不接受任何平台账号或角色字段。 */
public record HireEmployeeCommand(String name, String phone, LocalDate effectiveDate) {
}

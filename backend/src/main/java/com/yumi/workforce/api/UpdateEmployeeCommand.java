package com.yumi.workforce.api;

/** 修改员工资料。 */
public record UpdateEmployeeCommand(
        String employeeBusinessId, String name, String phone, long expectedRowVersion) {
}

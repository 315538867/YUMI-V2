package com.yumi.finance.payroll.api;

import java.time.LocalDate;

/** 运行某员工某自然月的完整胶水减免参考。 */
public record RunGlueMonthReferenceCommand(
        String idempotencyKey, String employeeBusinessId, LocalDate monthStart) {

    public RunGlueMonthReferenceCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("胶水月参考必须携带幂等键");
        }
        if (employeeBusinessId == null || employeeBusinessId.isBlank()) {
            throw new IllegalArgumentException("胶水月参考必须指定员工");
        }
        if (monthStart == null || monthStart.getDayOfMonth() != 1) {
            throw new IllegalArgumentException("胶水月参考必须指定自然月首日");
        }
    }
}

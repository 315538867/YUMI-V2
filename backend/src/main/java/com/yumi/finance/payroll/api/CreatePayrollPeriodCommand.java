package com.yumi.finance.payroll.api;

import java.time.LocalDate;

/** 建立工资期间：期间不得重叠。 */
public record CreatePayrollPeriodCommand(
        String idempotencyKey,
        String periodNo,
        LocalDate startDate,
        LocalDate endDate) {

    public CreatePayrollPeriodCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("工资期间命令必须携带幂等键");
        }
        if (periodNo == null || periodNo.isBlank()) {
            throw new IllegalArgumentException("工资期间必须填写期间号");
        }
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("工资期间起止日期不合法");
        }
    }
}

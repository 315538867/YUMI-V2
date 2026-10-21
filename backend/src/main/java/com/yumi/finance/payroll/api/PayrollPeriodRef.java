package com.yumi.finance.payroll.api;

import java.time.LocalDate;

/** 工资期间引用。 */
public record PayrollPeriodRef(
        String periodBusinessId,
        String periodNo,
        LocalDate startDate,
        LocalDate endDate,
        String status,
        long contentVersion) {
}

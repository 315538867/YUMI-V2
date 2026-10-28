package com.yumi.finance.payroll.api;

import java.time.Instant;
import java.util.List;

/** 只读复核运行及其逐项参考行。 */
public record PayrollReviewView(
        String reviewRunBusinessId,
        String periodBusinessId,
        String employeeBusinessId,
        String runType,
        String fingerprint,
        Instant generatedAt,
        List<EmployeePayrollView.ReferenceLineView> lines) {

    public PayrollReviewView {
        lines = List.copyOf(lines == null ? List.of() : lines);
    }
}

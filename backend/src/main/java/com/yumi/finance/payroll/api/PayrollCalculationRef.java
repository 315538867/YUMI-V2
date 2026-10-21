package com.yumi.finance.payroll.api;

/** 工资参考运行结果。 */
public record PayrollCalculationRef(
        String periodBusinessId, int employeeCount, int referenceLineCount) {
}

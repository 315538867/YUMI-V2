package com.yumi.finance.payroll.api;

/** 已核定工资的人工差额调整。 */
public interface PayrollAdjustmentService {

    PayrollAdjustmentRef adjust(AdjustPayrollCommand command);
}

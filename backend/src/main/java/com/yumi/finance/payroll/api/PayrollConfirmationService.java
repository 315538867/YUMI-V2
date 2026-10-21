package com.yumi.finance.payroll.api;

/** 逐人工资核定与应付/人工成本原子保存。 */
public interface PayrollConfirmationService {

    PayrollConfirmationRef confirmEmployee(ConfirmEmployeePayrollCommand command);
}

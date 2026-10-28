package com.yumi.finance.payroll.api;

import java.util.List;

/** 工资期间与参考查询。 */
public interface PayrollQuery {

    List<PayrollPeriodRef> listPeriods();

    List<EmployeePayrollView> listPeriodResults(String periodBusinessId);

    EmployeePayrollView getEmployeePayroll(String periodBusinessId, String employeeBusinessId);

    PayrollReviewView getReview(String reviewRunBusinessId);
}

package com.yumi.finance.payroll.api;

/** 已核定工资只读复核；不修改原核定、应付或成本。 */
public interface PayrollReviewService {

    PayrollReviewRef review(ReviewPayrollCommand command);
}

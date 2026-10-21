package com.yumi.finance.payroll.api;

/**
 * 工资期间与参考计算。
 *
 * <p>基础参考只形成逐项参考行，不生成应付或成本；已核定员工不被刷新覆盖。
 */
public interface PayrollApplicationService {

    PayrollPeriodRef createPeriod(CreatePayrollPeriodCommand command);

    PayrollCalculationRef calculate(CalculatePayrollCommand command);
}

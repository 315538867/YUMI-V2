package com.yumi.finance.payroll.api;

import java.time.LocalDate;

/** 胶水设置与员工自然月减免查询/运行。 */
public interface PayrollGlueAllowanceQuery {

    GlueAllowancePolicyView getPolicy(LocalDate monthStart);

    GlueMonthReferenceView getMonthReference(String employeeBusinessId, LocalDate monthStart);

    GlueMonthReferenceView runMonthReference(RunGlueMonthReferenceCommand command);
}

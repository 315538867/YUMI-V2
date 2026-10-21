package com.yumi.finance.payroll.api;

/** 胶水设置发布。 */
public interface PayrollGlueAllowancePolicyService {

    GlueAllowancePolicyRef publish(PublishGlueAllowancePolicyCommand command);
}

package com.yumi.finance.payroll.api;

import com.yumi.platform.api.value.WeightGram;
import java.time.LocalDate;

/** 某月生效的胶水设置视图。 */
public record GlueAllowancePolicyView(
        String policyBusinessId,
        LocalDate effectiveMonth,
        long version,
        WeightGram exemptWeightGrams,
        String reasonText) {
}

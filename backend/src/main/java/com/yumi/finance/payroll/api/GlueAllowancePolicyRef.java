package com.yumi.finance.payroll.api;

import com.yumi.platform.api.value.WeightGram;
import java.time.LocalDate;

/** 胶水设置版本引用。 */
public record GlueAllowancePolicyRef(
        String policyBusinessId,
        LocalDate effectiveMonth,
        long version,
        WeightGram exemptWeightGrams,
        long guardVersion) {
}

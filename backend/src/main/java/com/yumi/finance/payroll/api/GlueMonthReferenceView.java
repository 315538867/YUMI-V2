package com.yumi.finance.payroll.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 员工自然月胶水减免参考。 */
public record GlueMonthReferenceView(
        String referenceBusinessId,
        String employeeBusinessId,
        LocalDate monthStart,
        String policyBusinessId,
        String status,
        String incompleteReason,
        BigDecimal allocatedWeight,
        BigDecimal exemptWeight,
        String referenceFingerprint,
        boolean stale,
        List<GlueLineView> lines) {

    public record GlueLineView(
            String lineBusinessId,
            String responsibilityChainBusinessId,
            String costLineKey,
            LocalDate businessDate,
            BigDecimal allocatedWeight,
            BigDecimal exemptWeight,
            BigDecimal chargeableWeight,
            BigDecimal glueUnitPrice,
            BigDecimal preAmount,
            BigDecimal postAmount) {
    }
}

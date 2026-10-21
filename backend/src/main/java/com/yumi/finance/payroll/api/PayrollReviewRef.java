package com.yumi.finance.payroll.api;

/** 复核运行引用。 */
public record PayrollReviewRef(
        String resultBusinessId,
        String reviewRunBusinessId,
        String referenceFingerprint,
        int lineCount) {
}

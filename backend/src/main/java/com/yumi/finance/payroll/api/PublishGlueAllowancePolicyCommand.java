package com.yumi.finance.payroll.api;

import com.yumi.platform.api.value.WeightGram;
import java.time.LocalDate;

/**
 * 发布胶水免扣设置：同一生效月追加版本，预期配置守卫防止覆盖并发修改。
 */
public record PublishGlueAllowancePolicyCommand(
        String idempotencyKey,
        LocalDate effectiveMonth,
        WeightGram exemptWeightGrams,
        String reasonText,
        long expectedGuardVersion) {

    public PublishGlueAllowancePolicyCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("胶水设置必须携带幂等键");
        }
        if (effectiveMonth == null || effectiveMonth.getDayOfMonth() != 1) {
            throw new IllegalArgumentException("胶水设置必须指定自然月首日");
        }
        if (exemptWeightGrams == null) {
            throw new IllegalArgumentException("胶水设置必须明确重量，缺配置不能按零处理");
        }
        if (reasonText == null || reasonText.isBlank()) {
            throw new IllegalArgumentException("胶水设置必须填写修改原因");
        }
    }
}

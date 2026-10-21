package com.yumi.finance.payroll.api;

import com.yumi.platform.api.value.SignedSettlementAmount;

/** 人工差额调整：只改变应付与人工成本，不产生现金。 */
public record AdjustPayrollCommand(
        String idempotencyKey,
        String resultBusinessId,
        SignedSettlementAmount amountDelta,
        String reasonText,
        String referenceRunBusinessId) {

    public AdjustPayrollCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("工资调整必须携带幂等键");
        }
        if (resultBusinessId == null || resultBusinessId.isBlank()) {
            throw new IllegalArgumentException("工资调整必须指定员工结果");
        }
        if (amountDelta == null || amountDelta.value().signum() == 0) {
            throw new IllegalArgumentException("工资调整金额不能为零");
        }
        if (reasonText == null || reasonText.isBlank()) {
            throw new IllegalArgumentException("工资调整必须填写原因");
        }
    }
}

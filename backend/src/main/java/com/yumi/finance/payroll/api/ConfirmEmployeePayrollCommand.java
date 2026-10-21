package com.yumi.finance.payroll.api;

import com.yumi.platform.api.value.SettlementMoney;
import java.util.List;

/**
 * 逐人工资核定：最终工资由负责人明确填写，处理明细不自动抵扣。
 */
public record ConfirmEmployeePayrollCommand(
        String idempotencyKey,
        String resultBusinessId,
        long expectedRowVersion,
        String referenceFingerprint,
        SettlementMoney confirmedAmount,
        String note,
        List<ProcessingItemRequest> processingItems) {

    public ConfirmEmployeePayrollCommand {
        processingItems = List.copyOf(processingItems == null ? List.of() : processingItems);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("工资核定必须携带幂等键");
        }
        if (resultBusinessId == null || resultBusinessId.isBlank()) {
            throw new IllegalArgumentException("工资核定必须指定员工结果");
        }
        if (referenceFingerprint == null || referenceFingerprint.isBlank()) {
            throw new IllegalArgumentException("工资核定必须引用当前参考指纹");
        }
        if (confirmedAmount == null || confirmedAmount.value().signum() < 0) {
            throw new IllegalArgumentException("最终工资必须为非负金额");
        }
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("工资核定说明不能为空");
        }
    }

    /**
     * 人工处理明细：金额可空或为 0，均不自动改变最终工资。
     */
    public record ProcessingItemRequest(
            String referenceLineBusinessId, SettlementMoney manualAmount, String note) {

        public ProcessingItemRequest {
            if (referenceLineBusinessId == null || referenceLineBusinessId.isBlank()) {
                throw new IllegalArgumentException("处理明细必须引用服务端参考行");
            }
            if (manualAmount != null && manualAmount.value().signum() < 0) {
                throw new IllegalArgumentException("人工处理金额不能为负");
            }
        }
    }
}

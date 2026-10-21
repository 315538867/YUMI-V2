package com.yumi.finance.payroll.api;

/** 运行工资参考。 */
public record CalculatePayrollCommand(String idempotencyKey, String periodBusinessId) {

    public CalculatePayrollCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("工资参考命令必须携带幂等键");
        }
        if (periodBusinessId == null || periodBusinessId.isBlank()) {
            throw new IllegalArgumentException("工资参考必须指定期间");
        }
    }
}

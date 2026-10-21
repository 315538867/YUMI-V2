package com.yumi.finance.payroll.api;

/** 对已核定员工生成只读复核运行。 */
public record ReviewPayrollCommand(String idempotencyKey, String resultBusinessId) {

    public ReviewPayrollCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("工资复核必须携带幂等键");
        }
        if (resultBusinessId == null || resultBusinessId.isBlank()) {
            throw new IllegalArgumentException("工资复核必须指定员工结果");
        }
    }
}

package com.yumi.finance.payroll.api;

/** 摄取工资来源命令。 */
public record IngestPayrollSourcesCommand(String idempotencyKey) {

    public IngestPayrollSourcesCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("工资来源摄取必须携带幂等键");
        }
    }
}

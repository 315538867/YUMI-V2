package com.yumi.reporting.api;

/** 摄取全部公开来源 Feed。 */
public record IngestReportingSourcesCommand(String idempotencyKey) {

    public IngestReportingSourcesCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("报表归集必须携带幂等键");
        }
    }
}

package com.yumi.reporting.api;

/** 从来源投影重建影子版本并原子切换。 */
public record RebuildReportingProjectionCommand(String idempotencyKey) {

    public RebuildReportingProjectionCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("报表重建必须携带幂等键");
        }
    }
}

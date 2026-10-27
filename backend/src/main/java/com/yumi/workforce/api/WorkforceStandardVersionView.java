package com.yumi.workforce.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 标准版本记录；人数效率与每日有效时间共用。 */
public record WorkforceStandardVersionView(
        String standardType,
        String standardBusinessId,
        List<Version> versions) {

    public WorkforceStandardVersionView {
        versions = List.copyOf(versions);
    }

    public record Version(
            String versionBusinessId, long contentVersion, Integer memberCount,
            BigDecimal efficiencyFactor, Integer effectiveMinutes, String reason,
            Instant occurredAt, String operatorId) {
    }
}

package com.yumi.workforce.api;

import java.math.BigDecimal;

/** 全局标准视图：每日有效分钟与各人数效率因子。 */
public record WorkforceStandardView(
        String dailyStandardBusinessId,
        int effectiveMinutes,
        long dailyContentVersion,
        long dailyRowVersion,
        java.util.List<EfficiencyStandardView> efficiencyStandards) {

    public WorkforceStandardView {
        efficiencyStandards = java.util.List.copyOf(efficiencyStandards);
    }

    /** 人数效率标准。 */
    public record EfficiencyStandardView(
            String standardBusinessId, int memberCount, BigDecimal efficiencyFactor,
            long contentVersion, long rowVersion) {
    }
}

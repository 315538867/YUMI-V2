package com.yumi.workforce.api;

import java.math.BigDecimal;

/** 修改人数效率标准：因子必须大于 1；有活动引用时拒绝。 */
public record UpdateEfficiencyStandardCommand(
        String standardBusinessId, int memberCount, BigDecimal efficiencyFactor, long expectedRowVersion) {
}

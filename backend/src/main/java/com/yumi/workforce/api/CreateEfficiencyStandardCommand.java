package com.yumi.workforce.api;

import java.math.BigDecimal;

/** 新增人数效率标准：同人数只能有一条当前标准。 */
public record CreateEfficiencyStandardCommand(
        int memberCount, BigDecimal efficiencyFactor, String reason) {
}

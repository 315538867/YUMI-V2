package com.yumi.calculation.api;

import com.yumi.platform.api.value.SettlementMoney;
import java.util.List;

/**
 * 结算金额分配结果。
 *
 * <p>{@code shares} 的合计必须严格等于 {@code total}；{@code remainderUnits} 表示按稳定顺序
 * 额外获得 1 分的人数，用于审计与解释。
 */
public record AllocationResult(
        SettlementMoney total, List<Share> shares, int remainderUnits, FormulaTrace trace) {

    public AllocationResult {
        shares = List.copyOf(shares);
    }

    /** 单个成员的分配份额。 */
    public record Share(String employeeId, SettlementMoney amount) {
    }
}

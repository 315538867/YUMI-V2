package com.yumi.calculation.api;

import com.yumi.platform.api.value.CostAmount;
import java.util.List;

/**
 * 成本金额分配结果。
 *
 * <p>按 0.0001 元均分，{@code shares} 合计必须严格等于 {@code total}。
 */
public record CostAllocationResult(
        CostAmount total, List<Share> shares, int remainderUnits, FormulaTrace trace) {

    public CostAllocationResult {
        shares = List.copyOf(shares);
    }

    /** 单个成员的成本参考份额。 */
    public record Share(String employeeId, CostAmount amount) {
    }
}

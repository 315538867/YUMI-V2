package com.yumi.calculation.api;

import com.yumi.platform.api.value.WeightGram;
import java.util.List;

/**
 * 小组胶水重量分配：先按 {@code 0.0001g} 等分成员重量并分配余数，再分别计价。
 *
 * <p>规则来源：`docs/一期/07-计算中心模块.md` §4.10 与本提案 CA-05：
 * <pre>
 * 重量转换为 0.0001g 整数
 * 基础份额 = floor(整数重量 ÷ 成员数)
 * 余数按员工稳定业务标识升序，前“余数”名各增加 1 个 0.0001g
 * </pre>
 * 合计重量必须严格等于小组重量；禁止先按金额均分再算重量。
 */
public interface GlueWeightAllocator {

    /**
     * 分配小组胶水重量。
     *
     * @param total 小组该行的含损耗胶水重量
     * @param orderedEmployeeIds 已按稳定业务标识升序排列的成员
     */
    WeightAllocationResult splitEqually(WeightGram total, List<String> orderedEmployeeIds);

    static GlueWeightAllocator create() {
        return new com.yumi.calculation.GlueWeightAllocatorImpl();
    }

    /**
     * 重量分配结果。
     *
     * @param total 小组原始重量
     * @param shares 每名成员的重量份额，顺序与排序后的成员一致
     * @param remainderUnits 按稳定顺序多获得 1 个 0.0001g 的人数
     */
    record WeightAllocationResult(
            WeightGram total, List<Share> shares, int remainderUnits, FormulaTrace trace) {

        public WeightAllocationResult {
            shares = List.copyOf(shares);
        }

        /** 单名成员的重量份额。 */
        public record Share(String employeeId, WeightGram weight) {
        }
    }
}

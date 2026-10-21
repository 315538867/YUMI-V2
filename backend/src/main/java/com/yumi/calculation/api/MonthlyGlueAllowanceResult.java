package com.yumi.calculation.api;

import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.WeightGram;
import java.util.List;

/**
 * 月度胶水减免结果：逐行参考，不生成扣款总额。
 *
 * <p>规则来源：`docs/一期/07-计算中心模块.md` §4.10。每行分别给出减免前、减免后金额与
 * 减免金额，展示值取两者之差；合计只累加已舍入的员工行，不再以汇总重量重算。
 *
 * @param employeeId 员工稳定业务标识
 * @param policyBusinessId 当月配置版本
 * @param monthlyAllowanceWeight 月度标准
 * @param totalAllocatedWeight 当月分配重量合计
 * @param totalAllowedWeight 实际使用减免重量
 * @param lines 逐行结果
 * @param missingLineCount 缺历史依据的行数；大于 0 时结果不完整
 * @param trace 公式追溯标识
 */
public record MonthlyGlueAllowanceResult(
        String employeeId,
        String policyBusinessId,
        WeightGram monthlyAllowanceWeight,
        WeightGram totalAllocatedWeight,
        WeightGram totalAllowedWeight,
        List<GlueLineResult> lines,
        int missingLineCount,
        FormulaTrace trace) {

    public MonthlyGlueAllowanceResult {
        lines = List.copyOf(lines);
    }

    /** 结果是否完整：缺依据行不影响其他行，但整体不得宣称完整。 */
    public boolean complete() {
        return missingLineCount == 0;
    }

    /**
     * 单行结果。
     *
     * @param beforeReference 减免前参考金额（分配重量 × 单价，4 位舍入）；缺依据时为空
     * @param afterReference 减免后参考金额（计价重量 × 单价，4 位舍入）；缺依据时为空
     * @param allowanceAmount 展示的减免金额 = before − after；缺依据时为空
     * @param missingReason 缺依据原因；有依据时为空
     */
    public record GlueLineResult(
            String costLineId,
            java.time.LocalDate businessDate,
            WeightGram allocatedWeight,
            WeightGram allowedWeight,
            WeightGram chargedWeight,
            CostAmount beforeReference,
            CostAmount afterReference,
            CostAmount allowanceAmount,
            String missingReason) {

        public boolean hasReference() {
            return beforeReference != null;
        }
    }
}

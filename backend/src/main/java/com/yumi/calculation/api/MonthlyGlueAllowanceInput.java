package com.yumi.calculation.api;

import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.WeightGram;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 月度胶水减免输入：员工自然月的完整有效责任行。
 *
 * <p>规则来源：`docs/一期/07-计算中心模块.md` §4.10 与本提案 CA-04、CA-05：
 * <ul>
 *   <li>按业务日期、责任链与成本行稳定标识排序后应用月度标准；</li>
 *   <li>先按 {@code 0.0001g} 等分小组重量并分配余数，再逐行计价；</li>
 *   <li>每行满足 {@code 分配重量 = 减免重量 + 计价重量}；月度减免合计不超过标准；</li>
 *   <li>缺历史依据的行返回缺失原因，不填假零，也不生成扣款总额。</li>
 * </ul>
 *
 * @param employeeId 员工稳定业务标识
 * @param policyBusinessId 当月生效的胶水配置版本标识，用于追溯
 * @param monthlyAllowanceWeight 当月免扣重量标准
 * @param lines 当月全部有效责任行（未排序时由计算中心规范化）
 */
public record MonthlyGlueAllowanceInput(
        String employeeId,
        String policyBusinessId,
        WeightGram monthlyAllowanceWeight,
        List<GlueResponsibilityLine> lines) {

    public MonthlyGlueAllowanceInput {
        if (employeeId == null || employeeId.isBlank()) {
            throw new IllegalArgumentException("员工稳定业务标识不能为空");
        }
        if (policyBusinessId == null || policyBusinessId.isBlank()) {
            throw new IllegalArgumentException("胶水配置版本标识不能为空");
        }
        if (monthlyAllowanceWeight == null) {
            throw new IllegalArgumentException("月度免扣重量标准不能为空（缺配置不得按零处理）");
        }
        lines = List.copyOf(lines == null ? List.of() : lines);
    }

    /**
     * 单条有效责任行。
     *
     * @param businessDate 业务日期；决定减免顺序
     * @param responsibilityChainId 责任链稳定标识
     * @param costLineId 成本行稳定标识
     * @param allocatedGlueWeight 该行分配到本人的含损耗胶水重量
     * @param unitPricePerGram 历史胶水每克单价；为空表示依据缺失
     */
    public record GlueResponsibilityLine(
            LocalDate businessDate,
            String responsibilityChainId,
            String costLineId,
            WeightGram allocatedGlueWeight,
            BigDecimal unitPricePerGram) {

        public GlueResponsibilityLine {
            if (businessDate == null) {
                throw new IllegalArgumentException("业务日期不能为空");
            }
            if (responsibilityChainId == null || responsibilityChainId.isBlank()) {
                throw new IllegalArgumentException("责任链标识不能为空");
            }
            if (costLineId == null || costLineId.isBlank()) {
                throw new IllegalArgumentException("成本行标识不能为空");
            }
            if (allocatedGlueWeight == null) {
                throw new IllegalArgumentException("分配重量不能为空");
            }
            if (unitPricePerGram != null && unitPricePerGram.signum() < 0) {
                throw new IllegalArgumentException("胶水单价不允许为负");
            }
        }

        /** 是否存在可计价的历史依据。 */
        public boolean hasPrice() {
            return unitPricePerGram != null;
        }

        /** 稳定排序键：业务日期 → 责任链 → 成本行。 */
        public String sortKey() {
            return businessDate + "|" + responsibilityChainId + "|" + costLineId;
        }
    }

    /** 供调用方检查：行金额均为 4 位成本精度，不涉及结算金额。 */
    public CostAmount zeroCost() {
        return CostAmount.of(BigDecimal.ZERO, com.yumi.platform.api.value.CurrencyCode.CNY);
    }
}

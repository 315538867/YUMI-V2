package com.yumi.calculation;

import com.yumi.calculation.api.FormulaFingerprints;
import com.yumi.calculation.api.FormulaTrace;
import com.yumi.calculation.api.FormulaVersions;
import com.yumi.calculation.api.MonthlyGlueAllowanceCalculator;
import com.yumi.calculation.api.MonthlyGlueAllowanceInput;
import com.yumi.calculation.api.MonthlyGlueAllowanceInput.GlueResponsibilityLine;
import com.yumi.calculation.api.MonthlyGlueAllowanceResult;
import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.WeightGram;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 月度胶水减免实现。
 *
 * <p>规则来源：`docs/一期/07-计算中心模块.md` §4.10：
 * <ol>
 *   <li>行按业务日期、责任链、成本行稳定标识排序——顺序不依赖输入数组偶然排列；</li>
 *   <li>按顺序为每行分配减免重量：{@code min(剩余标准, 该行分配重量)}；</li>
 *   <li>计价重量 = 分配重量 − 减免重量；</li>
 *   <li>减免前/后参考金额分别 {@code HALF_UP(重量 × 单价, 4)}，展示减免取两者之差；</li>
 *   <li>缺历史单价的行返回缺失原因，金额为空，不填假零；不生成扣款总额。</li>
 * </ol>
 */
public final class MonthlyGlueAllowanceCalculatorImpl implements MonthlyGlueAllowanceCalculator {

    @Override
    public MonthlyGlueAllowanceResult calculate(MonthlyGlueAllowanceInput input) {
        if (input == null) {
            throw new IllegalArgumentException("月度胶水输入不能为空");
        }

        List<GlueResponsibilityLine> ordered = new ArrayList<>(input.lines());
        ordered.sort(Comparator.comparing(GlueResponsibilityLine::sortKey));

        BigDecimal remainingAllowance = input.monthlyAllowanceWeight().value();
        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal totalAllowed = BigDecimal.ZERO;
        int missing = 0;
        List<MonthlyGlueAllowanceResult.GlueLineResult> results = new ArrayList<>();

        for (GlueResponsibilityLine line : ordered) {
            BigDecimal allocated = line.allocatedGlueWeight().value();
            totalWeight = totalWeight.add(allocated);

            BigDecimal allowed = allocated.min(remainingAllowance);
            if (allowed.signum() < 0) {
                allowed = BigDecimal.ZERO;
            }
            remainingAllowance = remainingAllowance.subtract(allowed);
            totalAllowed = totalAllowed.add(allowed);

            BigDecimal charged = allocated.subtract(allowed);

            if (!line.hasPrice()) {
                missing++;
                results.add(new MonthlyGlueAllowanceResult.GlueLineResult(
                        line.costLineId(),
                        line.businessDate(),
                        WeightGram.ofGrams(allocated),
                        WeightGram.ofGrams(allowed),
                        WeightGram.ofGrams(charged),
                        null,
                        null,
                        null,
                        "缺历史胶水单价，无法计算该行参考金额"));
                continue;
            }

            CostAmount before = cost(allocated.multiply(line.unitPricePerGram()));
            CostAmount after = cost(charged.multiply(line.unitPricePerGram()));
            CostAmount allowance = CostAmount.of(before.value().subtract(after.value()), CurrencyCode.CNY);

            results.add(new MonthlyGlueAllowanceResult.GlueLineResult(
                    line.costLineId(),
                    line.businessDate(),
                    WeightGram.ofGrams(allocated),
                    WeightGram.ofGrams(allowed),
                    WeightGram.ofGrams(charged),
                    before,
                    after,
                    allowance,
                    null));
        }

        FormulaTrace trace = new FormulaTrace(
                FormulaVersions.MONTHLY_GLUE_ALLOWANCE,
                FormulaVersions.RULE_VERSION,
                FormulaVersions.ROUNDING_COST_4,
                FormulaFingerprints.of(
                        "employeeId", input.employeeId(),
                        "policyBusinessId", input.policyBusinessId(),
                        "monthlyAllowance", input.monthlyAllowanceWeight().value(),
                        "lines", ordered.stream().map(GlueResponsibilityLine::sortKey).toList()));

        return new MonthlyGlueAllowanceResult(
                input.employeeId(),
                input.policyBusinessId(),
                input.monthlyAllowanceWeight(),
                WeightGram.ofGrams(totalWeight),
                WeightGram.ofGrams(totalAllowed),
                results,
                missing,
                trace);
    }

    private static CostAmount cost(BigDecimal raw) {
        return CostAmount.of(raw, CurrencyCode.CNY);
    }

    /** 胶水金额按每克单价的四位精度直接舍入；此处保留用于文档化舍入意图。 */
    static BigDecimal roundToCostScale(BigDecimal raw) {
        return raw.setScale(CostAmount.SCALE, RoundingMode.HALF_UP);
    }
}

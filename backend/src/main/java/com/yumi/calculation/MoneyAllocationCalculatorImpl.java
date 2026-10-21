package com.yumi.calculation;

import com.yumi.calculation.api.AllocationResult;
import com.yumi.calculation.api.CostAllocationResult;
import com.yumi.calculation.api.FormulaFingerprints;
import com.yumi.calculation.api.FormulaTrace;
import com.yumi.calculation.api.FormulaVersions;
import com.yumi.calculation.api.MoneyAllocationCalculator;
import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 金额均分实现：按最小货币单位整除，余数按稳定员工标识升序分配。
 *
 * <p>规则来源：`docs/一期/07-计算中心模块.md` §4.7：
 * <pre>
 * 总额转换为最小单位整数 → 基础份额 = floor(总额 ÷ 人数) → 余数分给排序在前的成员
 * </pre>
 * 成员顺序在实现内部规范化：调用方传入乱序也会先排序，保证相同成员集合得到相同结果。
 */
public final class MoneyAllocationCalculatorImpl implements MoneyAllocationCalculator {

    @Override
    public AllocationResult splitEqually(SettlementMoney total, List<String> orderedEmployeeIds) {
        requireInputs(total, orderedEmployeeIds);
        List<String> members = orderedEmployeeIds.stream().sorted().toList();

        long totalCents = toMinorUnits(total.value(), SettlementMoney.SCALE);
        long base = totalCents / members.size();
        int remainder = (int) (totalCents % members.size());

        List<AllocationResult.Share> shares = new ArrayList<>();
        for (int index = 0; index < members.size(); index++) {
            long units = base + (index < remainder ? 1 : 0);
            shares.add(new AllocationResult.Share(
                    members.get(index),
                    SettlementMoney.of(fromMinorUnits(units, SettlementMoney.SCALE), total.currency())));
        }

        assertConserved(total.value(), shares.stream().map(share -> share.amount().value()).toList());

        return new AllocationResult(
                total,
                shares,
                remainder,
                new FormulaTrace(
                        FormulaVersions.MONEY_ALLOCATION,
                        FormulaVersions.RULE_VERSION,
                        FormulaVersions.ROUNDING_SETTLEMENT_2,
                        FormulaFingerprints.of(
                                "total", total.value(),
                                "members", members)));
    }

    @Override
    public CostAllocationResult splitCostEqually(CostAmount total, List<String> orderedEmployeeIds) {
        requireInputs(total, orderedEmployeeIds);
        List<String> members = orderedEmployeeIds.stream().sorted().toList();

        long totalUnits = toMinorUnits(total.value(), CostAmount.SCALE);
        long base = totalUnits / members.size();
        int remainder = (int) (totalUnits % members.size());

        List<CostAllocationResult.Share> shares = new ArrayList<>();
        for (int index = 0; index < members.size(); index++) {
            long units = base + (index < remainder ? 1 : 0);
            shares.add(new CostAllocationResult.Share(
                    members.get(index),
                    CostAmount.of(fromMinorUnits(units, CostAmount.SCALE), total.currency())));
        }

        assertConserved(total.value(), shares.stream().map(share -> share.amount().value()).toList());

        return new CostAllocationResult(
                total,
                shares,
                remainder,
                new FormulaTrace(
                        FormulaVersions.COST_ALLOCATION,
                        FormulaVersions.RULE_VERSION,
                        FormulaVersions.ROUNDING_COST_4,
                        FormulaFingerprints.of(
                                "total", total.value(),
                                "members", members)));
    }

    private static void requireInputs(Object total, List<String> members) {
        Objects.requireNonNull(total, "分配总额不能为空");
        if (members == null || members.isEmpty()) {
            throw new IllegalArgumentException("分配必须有至少一名成员");
        }
        if (members.stream().anyMatch(member -> member == null || member.isBlank())) {
            throw new IllegalArgumentException("成员稳定业务标识不能为空");
        }
        if (members.stream().distinct().count() != members.size()) {
            throw new IllegalArgumentException("成员稳定业务标识不能重复");
        }
    }

    private static long toMinorUnits(BigDecimal amount, int scale) {
        return amount.setScale(scale, RoundingMode.UNNECESSARY).movePointRight(scale).longValueExact();
    }

    private static BigDecimal fromMinorUnits(long units, int scale) {
        return BigDecimal.valueOf(units).movePointLeft(scale).setScale(scale, RoundingMode.UNNECESSARY);
    }

    private static void assertConserved(BigDecimal total, List<BigDecimal> shares) {
        BigDecimal sum = shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(total) != 0) {
            throw new IllegalStateException(
                    "分配守恒失败：分配合计 " + sum.toPlainString() + " 不等于总额 " + total.toPlainString());
        }
    }

    /** 便捷入口。 */
    public static MoneyAllocationCalculator create() {
        return new MoneyAllocationCalculatorImpl();
    }

    /** 便于测试引用币种常量。 */
    static CurrencyCode defaultCurrency() {
        return CurrencyCode.CNY;
    }
}

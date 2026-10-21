package com.yumi.calculation;

import com.yumi.calculation.api.FormulaFingerprints;
import com.yumi.calculation.api.FormulaTrace;
import com.yumi.calculation.api.FormulaVersions;
import com.yumi.calculation.api.GlueWeightAllocator;
import com.yumi.platform.api.value.WeightGram;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 小组胶水重量分配实现。
 *
 * <p>重量最小单位为 {@code 0.0001g}；余数按稳定员工标识升序分配。
 * 内部先对成员排序，因此调用方传入顺序不影响结果。
 */
public final class GlueWeightAllocatorImpl implements GlueWeightAllocator {

    @Override
    public WeightAllocationResult splitEqually(WeightGram total, List<String> orderedEmployeeIds) {
        if (total == null) {
            throw new IllegalArgumentException("小组胶水重量不能为空");
        }
        if (orderedEmployeeIds == null || orderedEmployeeIds.isEmpty()) {
            throw new IllegalArgumentException("重量分配必须有至少一名成员");
        }
        if (orderedEmployeeIds.stream().anyMatch(member -> member == null || member.isBlank())) {
            throw new IllegalArgumentException("成员稳定业务标识不能为空");
        }
        if (orderedEmployeeIds.stream().distinct().count() != orderedEmployeeIds.size()) {
            throw new IllegalArgumentException("成员稳定业务标识不能重复");
        }

        List<String> members = orderedEmployeeIds.stream().sorted().toList();
        long totalUnits = toUnits(total.value());
        long base = totalUnits / members.size();
        int remainder = (int) (totalUnits % members.size());

        List<WeightAllocationResult.Share> shares = new ArrayList<>();
        for (int index = 0; index < members.size(); index++) {
            long units = base + (index < remainder ? 1 : 0);
            shares.add(new WeightAllocationResult.Share(
                    members.get(index), WeightGram.ofGrams(fromUnits(units))));
        }

        BigDecimal sum = shares.stream()
                .map(share -> share.weight().value())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(total.value()) != 0) {
            throw new IllegalStateException("重量分配守恒失败：分配合计不等于小组重量");
        }

        return new WeightAllocationResult(
                total,
                shares,
                remainder,
                new FormulaTrace(
                        FormulaVersions.MONTHLY_GLUE_ALLOWANCE,
                        FormulaVersions.RULE_VERSION,
                        FormulaVersions.ROUNDING_COST_4,
                        FormulaFingerprints.of("total", total.value(), "members", members)));
    }

    private static long toUnits(BigDecimal grams) {
        return grams.setScale(WeightGram.SCALE, RoundingMode.UNNECESSARY)
                .movePointRight(WeightGram.SCALE)
                .longValueExact();
    }

    private static BigDecimal fromUnits(long units) {
        return BigDecimal.valueOf(units).movePointLeft(WeightGram.SCALE);
    }

    /** 便捷入口。 */
    public static GlueWeightAllocator create() {
        return new GlueWeightAllocatorImpl();
    }
}

package com.yumi.calculation;

import com.yumi.calculation.api.FormulaFingerprints;
import com.yumi.calculation.api.FormulaTrace;
import com.yumi.calculation.api.FormulaVersions;
import com.yumi.calculation.api.TeamWorkloadInput;
import com.yumi.calculation.api.TeamWorkloadResult;
import com.yumi.calculation.api.WorkloadCalculator;
import com.yumi.calculation.api.WorkloadComparison;
import com.yumi.calculation.api.WorkloadInput;
import com.yumi.calculation.api.WorkloadResult;
import com.yumi.platform.api.value.DurationSeconds;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作量纯计算实现。
 *
 * <p>规则来源：`docs/一期/07-计算中心模块.md` §4.3—§4.5。
 *
 * <ul>
 *   <li>生产/超额/修复：先按明细汇总基准秒数，再在任务级除一次效率并向上取整；</li>
 *   <li>其他任务：整体标准分钟 × 60，不再除效率；</li>
 *   <li>剩余秒数 {@code max(0, 每日有效 − 已占用)} 只是展示值，同时返回原始占用与超出信号；</li>
 *   <li>小组汇总只做一次整体取整，成员各自增加同一整体秒数。</li>
 * </ul>
 */
public final class WorkloadCalculatorImpl implements WorkloadCalculator {

    @Override
    public WorkloadResult assessIndividual(WorkloadInput input) {
        requireIndividual(input);
        long baseSeconds = baseSeconds(input);
        long requiredSeconds = baseSeconds;

        long dailySeconds = input.dailyEffectiveMinutes().toSeconds().value();
        long occupied = input.occupiedSecondsBefore().value();
        long remaining = Math.max(0L, dailySeconds - occupied);
        boolean exceeded = occupied > dailySeconds;

        WorkloadComparison comparison = compare(requiredSeconds, remaining);

        FormulaTrace trace = new FormulaTrace(
                FormulaVersions.INDIVIDUAL_WORKLOAD,
                FormulaVersions.RULE_VERSION,
                FormulaVersions.ROUNDING_SECONDS_CEILING,
                FormulaFingerprints.of(
                        "taskKind", input.taskKind(),
                        "baseSeconds", baseSeconds,
                        "dailyEffectiveMinutes", input.dailyEffectiveMinutes().value(),
                        "occupiedSecondsBefore", occupied));

        return new WorkloadResult(
                DurationSeconds.of(requiredSeconds),
                DurationSeconds.of(occupied),
                DurationSeconds.of(dailySeconds),
                DurationSeconds.of(remaining),
                exceeded,
                comparison,
                trace);
    }

    @Override
    public TeamWorkloadResult assessTeam(TeamWorkloadInput input) {
        WorkloadInput base = input.base();
        long baseSeconds = baseSeconds(base);

        long requiredSeconds;
        if (base.taskKind().appliesEfficiency()) {
            // 整体只除一次效率并向上取整，不逐明细取整
            requiredSeconds = ceilDivide(baseSeconds, input.efficiencyFactor().value());
        } else {
            // 其他小组任务的整体分钟已代表经过时间，不再除效率
            requiredSeconds = baseSeconds;
        }

        long dailySeconds = base.dailyEffectiveMinutes().toSeconds().value();
        long occupiedBefore = base.occupiedSecondsBefore().value();
        long remainingBefore = Math.max(0L, dailySeconds - occupiedBefore);
        boolean exceeded = occupiedBefore > dailySeconds;
        WorkloadComparison comparison = compare(requiredSeconds, remainingBefore);

        FormulaTrace trace = new FormulaTrace(
                FormulaVersions.TEAM_WORKLOAD,
                FormulaVersions.RULE_VERSION,
                FormulaVersions.ROUNDING_SECONDS_CEILING,
                FormulaFingerprints.of(
                        "taskKind", base.taskKind(),
                        "baseSeconds", baseSeconds,
                        "efficiencyFactor", input.efficiencyFactor() == null
                                ? null : input.efficiencyFactor().value(),
                        "memberCount", input.memberOccupiedSeconds().size()));

        WorkloadResult overall = new WorkloadResult(
                DurationSeconds.of(requiredSeconds),
                DurationSeconds.of(occupiedBefore),
                DurationSeconds.of(dailySeconds),
                DurationSeconds.of(remainingBefore),
                exceeded,
                comparison,
                trace);

        List<TeamWorkloadResult.MemberLoad> members = new ArrayList<>();
        long minimumRemaining = Long.MAX_VALUE;
        for (int index = 0; index < input.memberOccupiedSeconds().size(); index++) {
            long memberOccupied = input.memberOccupiedSeconds().get(index).value();
            long memberRemaining = Math.max(0L, dailySeconds - memberOccupied);
            minimumRemaining = Math.min(minimumRemaining, memberRemaining);
            members.add(new TeamWorkloadResult.MemberLoad(
                    index,
                    DurationSeconds.of(memberOccupied),
                    DurationSeconds.of(memberRemaining),
                    DurationSeconds.of(requiredSeconds)));
        }

        // 小组可用秒数取成员剩余最小值，单独返回，避免与整体剩余语义混用
        return new TeamWorkloadResult(overall, DurationSeconds.of(minimumRemaining), members);
    }

    private static void requireIndividual(WorkloadInput input) {
        if (input == null) {
            throw new IllegalArgumentException("工作量输入不能为空");
        }
        if (input.team()) {
            throw new IllegalArgumentException("小组任务请使用 assessTeam 入口");
        }
    }

    private static long baseSeconds(WorkloadInput input) {
        if (input.taskKind().usesItemStandards()) {
            long total = 0L;
            for (WorkloadInput.ItemStandard item : input.items()) {
                total = Math.addExact(total, item.baseSeconds().value());
            }
            return total;
        }
        return Math.multiplyExact((long) input.otherTaskStandardMinutes().value(), 60L);
    }

    private static long ceilDivide(long dividend, java.math.BigDecimal divisor) {
        java.math.BigDecimal quotient = java.math.BigDecimal.valueOf(dividend)
                .divide(divisor, 0, java.math.RoundingMode.CEILING);
        return quotient.longValueExact();
    }

    private static WorkloadComparison compare(long requiredSeconds, long remainingSeconds) {
        if (requiredSeconds < remainingSeconds) {
            return WorkloadComparison.UNDER_STANDARD;
        }
        if (requiredSeconds == remainingSeconds) {
            return WorkloadComparison.WITHIN_STANDARD;
        }
        return WorkloadComparison.OVER_STANDARD;
    }
}

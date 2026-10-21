package com.yumi.production.application;

import com.yumi.calculation.api.FormulaVersions;
import com.yumi.calculation.api.TaskKind;
import com.yumi.calculation.api.TeamWorkloadInput;
import com.yumi.calculation.api.TeamWorkloadResult;
import com.yumi.calculation.api.WorkloadCalculator;
import com.yumi.calculation.api.WorkloadComparison;
import com.yumi.calculation.api.WorkloadInput;
import com.yumi.calculation.api.WorkloadResult;
import com.yumi.platform.api.value.DurationMinutes;
import com.yumi.platform.api.value.DurationSeconds;
import com.yumi.platform.api.value.EfficiencyFactor;
import com.yumi.platform.api.value.Quantity;
import com.yumi.production.api.SubjectType;
import com.yumi.production.api.TaskType;
import java.util.List;

/**
 * 工作量评估装配：把任务明细与主体标准组装为计算中心输入，并把结果映射为评估视图。
 *
 * <p>规则来源：`docs/一期/11-生产作业模块.md` §6.2.1、§6.4.1、§6.5：
 * <ul>
 *   <li>生产/修复任务按明细计划数量 × 冻结标准分钟 × 60 汇总；</li>
 *   <li>小组在任务级除一次效率并向上取整，成员各自计入同一整体秒数；</li>
 *   <li>其他任务直接录入整体分钟 × 60，不应用效率；</li>
 *   <li>{@code OVER_STANDARD} 默认拒绝，超额任务不因该结果被拒绝。</li>
 * </ul>
 */
public class WorkloadAssessor {

    private final WorkloadCalculator calculator;

    public WorkloadAssessor(WorkloadCalculator calculator) {
        this.calculator = calculator;
    }

    /** 默认装配：计算中心纯函数实现。 */
    public static WorkloadAssessor defaults() {
        return new WorkloadAssessor(WorkloadCalculator.create());
    }

    /**
     * 评估个人任务。
     *
     * @param items 明细（计划数量与冻结单件标准分钟）；其他任务为空
     * @param otherTaskMinutes 其他任务整体分钟；生产任务为空
     * @param dailyEffectiveMinutes 每日有效分钟（来自标准快照）
     * @param occupiedSecondsBefore 该员工当日已占用秒数
     */
    public WorkloadResult assessIndividual(
            TaskType taskType,
            List<DetailStandard> items,
            Integer otherTaskMinutes,
            int dailyEffectiveMinutes,
            long occupiedSecondsBefore) {
        WorkloadInput input = new WorkloadInput(
                toTaskKind(taskType),
                items.stream()
                        .map(item -> new WorkloadInput.ItemStandard(
                                Quantity.of(item.quantity()), DurationMinutes.of(item.standardMinutes())))
                        .toList(),
                otherTaskMinutes == null ? null : DurationMinutes.of(otherTaskMinutes),
                DurationMinutes.of(dailyEffectiveMinutes),
                DurationSeconds.of(occupiedSecondsBefore),
                false);
        return calculator.assessIndividual(input);
    }

    /**
     * 评估小组任务（个人与小组共用同一计算公式，按成员取最严结果）。
     *
     * @param memberOccupiedSeconds 各成员当日已占用秒数
     */
    public TeamWorkloadResult assessTeam(
            TaskType taskType,
            List<DetailStandard> items,
            Integer otherTaskMinutes,
            int dailyEffectiveMinutes,
            long occupiedSecondsBefore,
            java.math.BigDecimal efficiencyFactor,
            List<Long> memberOccupiedSeconds) {
        WorkloadInput base = new WorkloadInput(
                toTaskKind(taskType),
                items.stream()
                        .map(item -> new WorkloadInput.ItemStandard(
                                Quantity.of(item.quantity()), DurationMinutes.of(item.standardMinutes())))
                        .toList(),
                otherTaskMinutes == null ? null : DurationMinutes.of(otherTaskMinutes),
                DurationMinutes.of(dailyEffectiveMinutes),
                DurationSeconds.of(occupiedSecondsBefore),
                true);
        return calculator.assessTeam(new TeamWorkloadInput(
                base,
                taskType == TaskType.OTHER_TASK ? null : EfficiencyFactor.of(efficiencyFactor),
                memberOccupiedSeconds.stream().map(DurationSeconds::of).toList()));
    }

    /**
     * 决定是否允许创建任务。
     *
     * <p>评估失败或 {@code UNAVAILABLE} 一律拒绝；{@code OVER_STANDARD} 对超额任务放行，
     * 其余类型必须有显式覆盖原因。
     */
    public boolean permits(TaskType taskType, WorkloadComparison comparison, String overrideReason) {
        if (comparison == null || comparison == WorkloadComparison.UNAVAILABLE) {
            return false;
        }
        if (comparison == WorkloadComparison.OVER_STANDARD) {
            return taskType.allowsOverStandard()
                    || (overrideReason != null && !overrideReason.isBlank());
        }
        return true;
    }

    /** 明细标准：计划数量与冻结单件标准分钟。 */
    public record DetailStandard(int quantity, int standardMinutes) {
    }

    /** 归一化后的评估结果：个人与小组共用。 */
    public record AssignableResult(
            long requiredSeconds,
            long availableSeconds,
            long occupiedSecondsBefore,
            WorkloadComparison comparison,
            String assessmentResult,
            String formulaCode,
            String inputFingerprint,
            TeamWorkloadResult team) {

        /** 本次任务给每名参与成员增加的秒数：小组为整体经过时间，个人为标准秒数。 */
        public long membersAddedSeconds() {
            return requiredSeconds;
        }
    }

    /** 归一化个人结果。 */
    public static AssignableResult normalise(WorkloadResult result) {
        return new AssignableResult(
                result.requiredSeconds().value(),
                result.remainingSecondsBefore().value(),
                result.occupiedSecondsBefore().value(),
                result.comparison(),
                result.comparison().name(),
                FormulaVersions.INDIVIDUAL_WORKLOAD,
                result.trace().inputFingerprint(),
                null);
    }

    /** 归一化小组结果：可用秒数取成员剩余最小值。 */
    public static AssignableResult normalise(TeamWorkloadResult result) {
        return new AssignableResult(
                result.overall().requiredSeconds().value(),
                result.availableSeconds().value(),
                result.overall().occupiedSecondsBefore().value(),
                result.overall().comparison(),
                result.overall().comparison().name(),
                FormulaVersions.TEAM_WORKLOAD,
                result.overall().trace().inputFingerprint(),
                result);
    }

    private static TaskKind toTaskKind(TaskType taskType) {
        return switch (taskType) {
            case NORMAL_PRODUCTION -> TaskKind.NORMAL_PRODUCTION;
            case EXTRA_PRODUCTION -> TaskKind.EXTRA_PRODUCTION;
            case REPAIR -> TaskKind.REPAIR;
            case OTHER_TASK -> TaskKind.OTHER_TASK;
        };
    }

    /** 主体是否小组。 */
    public static boolean isTeam(SubjectType subjectType) {
        return subjectType == SubjectType.TEAM;
    }
}

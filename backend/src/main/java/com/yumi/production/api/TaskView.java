package com.yumi.production.api;

import java.time.LocalDate;
import java.util.List;

/**
 * 任务视图：含明细、工作量评估与预留结果。
 *
 * <p>{@code standardSeconds} 与 {@code assessment} 来自计算中心，生产模块只保存与展示。
 */
public record TaskView(
        String taskBusinessId,
        String taskNo,
        TaskType taskType,
        TaskStatus status,
        LocalDate workDate,
        SubjectRef subject,
        TargetProcess processCode,
        int plannedWorkQuantity,
        long taskStandardSeconds,
        List<ItemView> items,
        AssessmentView assessment,
        OtherTaskView otherTask) {

    public TaskView {
        items = List.copyOf(items);
    }

    /** 任务明细视图。 */
    public record ItemView(
            String taskItemBusinessId,
            int itemOrder,
            String demandBusinessId,
            DemandType demandType,
            String productionSourceBusinessId,
            String repairSourceBusinessId,
            String productBusinessId,
            String productSnapshotRef,
            String schemeSnapshotRef,
            TargetProcess targetProcess,
            int plannedQuantity,
            int itemStandardMinutes,
            String verificationStatus) {
    }

    /** 工作量评估视图。 */
    public record AssessmentView(
            String assessmentBusinessId,
            long standardSeconds,
            long availableEffectiveSeconds,
            String result,
            String formulaRuleVersion,
            String inputFingerprint,
            String overrideReason,
            List<MemberLoadView> memberLoads) {

        public AssessmentView {
            memberLoads = List.copyOf(memberLoads);
        }
    }

    /** 成员负载视图。 */
    public record MemberLoadView(
            String employeeBusinessId, long occupiedSecondsBefore, long availableSecondsBefore,
            long occupiedSecondsAfter) {
    }

    /** 其他任务详情视图。 */
    public record OtherTaskView(
            String workContent, String relatedProductBusinessId, Integer plannedQuantity,
            com.yumi.platform.api.value.CostAmount frozenUnitLaborAmount, String resultNote) {
    }
}

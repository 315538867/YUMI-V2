package com.yumi.production.api;

import java.util.List;

/**
 * 任务安排结果：任务引用、工作量评估与预留依据。
 *
 * <p>评估结果与标准秒数来自计算中心；生产模块保存并对外展示，不复制计算公式。
 *
 * @param assessmentResult {@code UNDER_STANDARD / WITHIN_STANDARD / OVER_STANDARD / UNAVAILABLE}
 */
public record AssignmentResultView(
        String assessmentBusinessId,
        long taskStandardSeconds,
        long availableEffectiveSeconds,
        com.yumi.calculation.api.WorkloadComparison assessmentComparison,
        String formulaRuleVersion,
        String inputFingerprint,
        String assessmentResult,
        String overrideReason,
        List<MemberLoadView> memberLoads) {

    public AssignmentResultView {
        memberLoads = List.copyOf(memberLoads == null ? List.of() : memberLoads);
    }

    /** 成员负载视图：成员逐个计入同一整体标准秒数。 */
    public record MemberLoadView(
            int memberIndex,
            long occupiedSecondsBefore,
            long availableSecondsBefore,
            long addedSeconds) {
    }
}

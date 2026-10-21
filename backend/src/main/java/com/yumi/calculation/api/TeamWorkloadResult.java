package com.yumi.calculation.api;

import com.yumi.platform.api.value.DurationSeconds;
import java.util.List;

/**
 * 小组工作量结果：除整体结果外，返回每名成员的占用、剩余与本次增加秒数。
 *
 * <p>小组任务标准秒数是小组整体经过时间；同一数值分别计入每名成员，不乘成员人数。
 * {@code availableSeconds} 为“小组可用秒数 = min(所有成员剩余秒数)”，与整体
 * {@link WorkloadResult#remainingSecondsBefore()} 区分：后者是任务主体视角的创建前剩余。
 */
public record TeamWorkloadResult(
        WorkloadResult overall,
        DurationSeconds availableSeconds,
        List<MemberLoad> members) {

    public TeamWorkloadResult {
        if (overall == null) {
            throw new IllegalArgumentException("小组整体结果不能为空");
        }
        if (availableSeconds == null) {
            throw new IllegalArgumentException("小组可用秒数不能为空");
        }
        members = List.copyOf(members == null ? List.of() : members);
    }

    /**
     * 单个成员的负载视图。
     *
     * @param memberIndex 成员在调用方稳定排序中的位置（只用于展示，不参与计算）
     * @param occupiedSeconds 该成员已占用秒数
     * @param remainingSeconds 该成员剩余秒数（不小于 0）
     * @param addedSeconds 本次任务给该成员增加的秒数（= 小组任务标准秒数）
     */
    public record MemberLoad(
            int memberIndex,
            DurationSeconds occupiedSeconds,
            DurationSeconds remainingSeconds,
            DurationSeconds addedSeconds) {
    }
}

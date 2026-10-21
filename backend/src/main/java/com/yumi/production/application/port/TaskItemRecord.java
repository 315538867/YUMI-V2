package com.yumi.production.application.port;

import com.yumi.production.api.DemandType;
import com.yumi.production.api.TargetProcess;

/** 任务明细记录（模块内部类型）。 */
public record TaskItemRecord(
        String taskItemBusinessId,
        String taskBusinessId,
        int itemOrder,
        String demandBusinessId,
        DemandType demandType,
        String productionSourceBusinessId,
        String physicalRootSourceBusinessId,
        String repairSourceBusinessId,
        String orderBusinessId,
        String orderItemBusinessId,
        String productBusinessId,
        String productSnapshotRef,
        String schemeBusinessId,
        String schemeSnapshotRef,
        TargetProcess targetProcess,
        int plannedQuantity,
        int itemStandardMinutes,
        String verificationStatus,
        long assignmentVersion,
        long rowVersion) {
}

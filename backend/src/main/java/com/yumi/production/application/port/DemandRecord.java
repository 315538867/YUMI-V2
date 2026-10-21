package com.yumi.production.application.port;

import com.yumi.production.api.DemandStatus;
import com.yumi.production.api.DemandType;
import com.yumi.production.api.TargetProcess;
import java.time.Instant;

/** 生产需求当前记录（模块内部类型）。 */
public record DemandRecord(
        long id,
        String demandBusinessId,
        DemandType demandType,
        String sourceModule,
        String sourceType,
        String sourceBusinessId,
        String orderBusinessId,
        String orderItemBusinessId,
        String productBusinessId,
        String productSnapshotRef,
        String schemeBusinessId,
        String schemeSnapshotRef,
        TargetProcess targetProcess,
        String basisReturnedSourceBusinessId,
        String productionBasisJson,
        String productionBasisFingerprint,
        int requiredQuantity,
        DemandStatus status,
        long contentVersion,
        long rowVersion,
        Instant createdAt,
        Instant updatedAt) {
}

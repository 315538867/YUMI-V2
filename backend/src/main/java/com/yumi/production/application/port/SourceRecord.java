package com.yumi.production.application.port;

import com.yumi.production.api.SourceKind;
import com.yumi.production.api.TargetProcess;
import java.time.Instant;

/** 履约生产来源记录（模块内部类型）。 */
public record SourceRecord(
        long id,
        String productionSourceBusinessId,
        String demandBusinessId,
        SourceKind sourceKind,
        String physicalRootSourceBusinessId,
        String returnedSourceBusinessId,
        String productBusinessId,
        String productSnapshotRef,
        String schemeBusinessId,
        String schemeSnapshotRef,
        String productionBasisFingerprint,
        int initialQuantity,
        TargetProcess targetProcess,
        String openingStatus,
        String status,
        long rowVersion,
        Instant createdAt) {
}

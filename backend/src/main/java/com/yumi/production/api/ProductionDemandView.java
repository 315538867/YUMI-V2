package com.yumi.production.api;

import com.yumi.platform.api.value.ContentFingerprint;

/**
 * 生产需求视图：含冻结的生产依据引用与指纹。
 *
 * <p>生产模块只保存类型化快照引用与依据指纹，不复制快照载荷。
 */
public record ProductionDemandView(
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
        ContentFingerprint productionBasisFingerprint,
        int requiredQuantity,
        DemandStatus status,
        long contentVersion,
        long rowVersion,
        SourceView source) {

    /** 履约来源视图。 */
    public record SourceView(
            String productionSourceBusinessId,
            SourceKind sourceKind,
            String physicalRootSourceBusinessId,
            String returnedSourceBusinessId,
            int initialQuantity,
            String openingStatus,
            String status) {
    }
}

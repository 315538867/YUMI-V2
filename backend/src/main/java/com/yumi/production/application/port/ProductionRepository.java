package com.yumi.production.application.port;

import com.yumi.production.api.DemandType;
import com.yumi.production.api.SourceKind;
import com.yumi.production.api.TargetProcess;
import java.util.List;
import java.util.Optional;

/** 生产需求、来源与数量投影持久化端口。 */
public interface ProductionRepository {

    Optional<DemandRecord> findDemand(String demandBusinessId);

    Optional<DemandRecord> findDemandBySource(String sourceModule, String sourceType, String sourceBusinessId);

    Optional<DemandRecord> findDemandByOrderItem(String orderItemBusinessId);

    List<DemandRecord> findDemandsByProduct(String productBusinessId, int limit);

    void insertDemand(DemandRecord record);

    Optional<SourceRecord> findSource(String productionSourceBusinessId);

    List<SourceRecord> findSourcesOfDemand(String demandBusinessId);

    List<AvailableSourceRecord> listAvailableSources();

    void insertSource(SourceRecord record);

    Optional<QuantityProjections.DemandProjection> findDemandProjection(String demandBusinessId);

    void insertDemandProjection(QuantityProjections.DemandProjection projection);

    Optional<QuantityProjections.PhysicalProjection> findPhysicalProjection(String physicalRootSourceBusinessId);

    void insertPhysicalProjection(QuantityProjections.PhysicalProjection projection);

    /** 锁定来源行；不存在时抛错。 */
    SourceRecord lockSource(String productionSourceBusinessId);

    long countDemands(SourceKind sourceKind);

    void updateDemandRequiredQuantity(String demandBusinessId, int requiredQuantity);

    void insertDemandChangeFact(DemandChangeFact fact);

    long countOpeningFactsOfSource(String productionSourceBusinessId);

    void insertOpeningFact(OpeningFact fact);

    void updateSourceOpening(String productionSourceBusinessId, String openingStatus, String status);

    void increaseSourceQuantity(String productionSourceBusinessId, int deltaQuantity);

    void insertStageProjection(StageProjection projection);

    /** 期初事实：只保存接管时状态，不进入劳动、材料或责任来源。 */
    record OpeningFact(
            String factBusinessId, String openingBatchBusinessId, String demandBusinessId,
            String productionSourceBusinessId, String stageCode, String qualityCode,
            String dispositionCode, String physicalDestination, String reworkProcessCode,
            int quantity, String reasonCode, String reasonText,
            java.time.Instant occurredAt, String operatorId) {
    }

    /** 可安排来源读模型：来源、需求类型与数量投影合并。 */
    record AvailableSourceRecord(
            String productionSourceBusinessId, String demandBusinessId, DemandType demandType,
            String productBusinessId, String schemeBusinessId, TargetProcess targetProcess,
            int initialQuantity, String openingStatus, String status,
            int pendingWorkQuantity, int inProcessQuantity, int fulfillmentGapQuantity,
            int reservedQuantity) {
    }

    /** 阶段数量投影。 */
    record StageProjection(
            String productionSourceBusinessId, String physicalRootSourceBusinessId,
            String stageCode, String qualityCode, String dispositionCode,
            String physicalDestination, int availableQuantity) {
    }

    /** 需求变更事实。 */
    record DemandChangeFact(
            String factBusinessId, String demandBusinessId, String productionSourceBusinessId,
            String changeType, int quantityBefore, int quantityAfter, int quantityDelta,
            int cancelUnproducedQuantity, int transferProducedQuantity,
            int releaseToReturnPoolQuantity, int releaseRepairGapQuantity,
            String reasonCode, String reasonText) {
    }
}

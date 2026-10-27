package com.yumi.production.infrastructure;

import com.yumi.platform.api.jdbc.JdbcTimes;
import com.yumi.production.api.DemandStatus;
import com.yumi.production.api.DemandType;
import com.yumi.production.api.SourceKind;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.application.port.DemandRecord;
import com.yumi.production.application.port.ProductionRepository;
import com.yumi.production.application.port.QuantityProjections;
import com.yumi.production.application.port.SourceRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 生产需求、来源与数量投影持久化（MySQL）。 */
@Repository
public class JdbcProductionRepository implements ProductionRepository {

    private final JdbcClient jdbc;

    public JdbcProductionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<DemandRecord> findDemand(String demandBusinessId) {
        return jdbc.sql(DEMAND_SELECT + " WHERE demand_business_id = :id")
                .param("id", demandBusinessId)
                .query(JdbcProductionRepository::mapDemand)
                .optional();
    }

    @Override
    public Optional<DemandRecord> findDemandBySource(
            String sourceModule, String sourceType, String sourceBusinessId) {
        return jdbc.sql(DEMAND_SELECT + """
                         WHERE source_module = :sourceModule AND source_type = :sourceType
                           AND source_business_id = :sourceBusinessId
                        """)
                .param("sourceModule", sourceModule)
                .param("sourceType", sourceType)
                .param("sourceBusinessId", sourceBusinessId)
                .query(JdbcProductionRepository::mapDemand)
                .optional();
    }

    @Override
    public Optional<DemandRecord> findDemandByOrderItem(String orderItemBusinessId) {
        return jdbc.sql(DEMAND_SELECT + " WHERE order_item_business_id = :orderItemId")
                .param("orderItemId", orderItemBusinessId)
                .query(JdbcProductionRepository::mapDemand)
                .optional();
    }

    @Override
    public List<DemandRecord> findDemandsByProduct(String productBusinessId, int limit) {
        return jdbc.sql(DEMAND_SELECT
                        + " WHERE product_business_id = :productId ORDER BY created_at, id LIMIT :limit")
                .param("productId", productBusinessId)
                .param("limit", limit)
                .query(JdbcProductionRepository::mapDemand)
                .list();
    }

    @Override
    public void insertDemand(DemandRecord record) {
        jdbc.sql("""
                        INSERT INTO production_demands (
                            demand_business_id, demand_type, source_module, source_type, source_business_id,
                            order_business_id, order_item_business_id, product_business_id,
                            product_snapshot_ref, scheme_business_id, scheme_snapshot_ref, target_process,
                            basis_returned_source_business_id, production_basis_json,
                            production_basis_fingerprint, required_quantity, status, content_version,
                            row_version, created_at, created_by, updated_at)
                        VALUES (:demandId, :demandType, :sourceModule, :sourceType, :sourceBusinessId,
                                :orderId, :orderItemId, :productId, :productSnapshotRef, :schemeId,
                                :schemeSnapshotRef, :targetProcess, :basisReturnedSourceId, :basisJson,
                                :basisFingerprint, :requiredQuantity, :status, 1, 1, :now, :createdBy, :now)
                        """)
                .param("demandId", record.demandBusinessId())
                .param("demandType", record.demandType().name())
                .param("sourceModule", record.sourceModule())
                .param("sourceType", record.sourceType())
                .param("sourceBusinessId", record.sourceBusinessId())
                .param("orderId", record.orderBusinessId())
                .param("orderItemId", record.orderItemBusinessId())
                .param("productId", record.productBusinessId())
                .param("productSnapshotRef", record.productSnapshotRef())
                .param("schemeId", record.schemeBusinessId())
                .param("schemeSnapshotRef", record.schemeSnapshotRef())
                .param("targetProcess", record.targetProcess().name())
                .param("basisReturnedSourceId", record.basisReturnedSourceBusinessId())
                .param("basisJson", record.productionBasisJson())
                .param("basisFingerprint", record.productionBasisFingerprint())
                .param("requiredQuantity", record.requiredQuantity())
                .param("status", record.status().name())
                .param("now", JdbcTimes.toUtc(record.createdAt()))
                .param("createdBy", "SYSTEM")
                .update();
    }

    @Override
    public Optional<SourceRecord> findSource(String productionSourceBusinessId) {
        return jdbc.sql(SOURCE_SELECT + " WHERE production_source_business_id = :id")
                .param("id", productionSourceBusinessId)
                .query(JdbcProductionRepository::mapSource)
                .optional();
    }

    @Override
    public List<SourceRecord> findSourcesOfDemand(String demandBusinessId) {
        return jdbc.sql(SOURCE_SELECT + " WHERE demand_business_id = :demandId ORDER BY id")
                .param("demandId", demandBusinessId)
                .query(JdbcProductionRepository::mapSource)
                .list();
    }

    @Override
    public List<AvailableSourceRecord> listAvailableSources() {
        return jdbc.sql("""
                        SELECT s.production_source_business_id, s.demand_business_id, d.demand_type,
                               s.product_business_id, s.scheme_business_id, s.target_process,
                               s.initial_quantity, s.opening_status, s.status,
                               p.pending_work_quantity, p.in_process_quantity,
                               p.fulfillment_gap_quantity,
                               COALESCE(r.reserved_quantity, 0) AS reserved_quantity
                        FROM production_sources s
                        JOIN production_demands d ON d.demand_business_id = s.demand_business_id
                        JOIN production_demand_quantity_projections p
                          ON p.demand_business_id = s.demand_business_id
                        LEFT JOIN production_source_reservations r
                          ON r.production_source_business_id = s.production_source_business_id
                        WHERE s.status = 'ACTIVE'
                        ORDER BY s.created_at, s.production_source_business_id
                        """)
                .query((resultSet, rowNumber) -> new AvailableSourceRecord(
                        resultSet.getString("production_source_business_id"),
                        resultSet.getString("demand_business_id"),
                        DemandType.valueOf(resultSet.getString("demand_type")),
                        resultSet.getString("product_business_id"),
                        resultSet.getString("scheme_business_id"),
                        TargetProcess.valueOf(resultSet.getString("target_process")),
                        resultSet.getInt("initial_quantity"),
                        resultSet.getString("opening_status"),
                        resultSet.getString("status"),
                        resultSet.getInt("pending_work_quantity"),
                        resultSet.getInt("in_process_quantity"),
                        resultSet.getInt("fulfillment_gap_quantity"),
                        resultSet.getInt("reserved_quantity")))
                .list();
    }

    @Override
    public void insertSource(SourceRecord record) {
        jdbc.sql("""
                        INSERT INTO production_sources (
                            production_source_business_id, demand_business_id, source_kind,
                            physical_root_source_business_id, returned_source_business_id,
                            product_business_id, product_snapshot_ref, scheme_business_id,
                            scheme_snapshot_ref, production_basis_fingerprint, initial_quantity,
                            target_process, opening_status, status, row_version, created_at)
                        VALUES (:sourceId, :demandId, :sourceKind, :physicalRoot, :returnedSourceId,
                                :productId, :productSnapshotRef, :schemeId, :schemeSnapshotRef,
                                :basisFingerprint, :initialQuantity, :targetProcess, :openingStatus,
                                :status, 1, :now)
                        """)
                .param("sourceId", record.productionSourceBusinessId())
                .param("demandId", record.demandBusinessId())
                .param("sourceKind", record.sourceKind().name())
                .param("physicalRoot", record.physicalRootSourceBusinessId())
                .param("returnedSourceId", record.returnedSourceBusinessId())
                .param("productId", record.productBusinessId())
                .param("productSnapshotRef", record.productSnapshotRef())
                .param("schemeId", record.schemeBusinessId())
                .param("schemeSnapshotRef", record.schemeSnapshotRef())
                .param("basisFingerprint", record.productionBasisFingerprint())
                .param("initialQuantity", record.initialQuantity())
                .param("targetProcess", record.targetProcess().name())
                .param("openingStatus", record.openingStatus())
                .param("status", record.status())
                .param("now", JdbcTimes.toUtc(record.createdAt()))
                .update();
    }

    @Override
    public Optional<QuantityProjections.DemandProjection> findDemandProjection(String demandBusinessId) {
        return jdbc.sql("""
                        SELECT demand_business_id, required_quantity, pending_work_quantity,
                               in_process_quantity, target_ready_quantity, fulfilled_quantity,
                               fulfillment_gap_quantity, row_version
                        FROM production_demand_quantity_projections WHERE demand_business_id = :id
                        """)
                .param("id", demandBusinessId)
                .query((resultSet, rowNumber) -> new QuantityProjections.DemandProjection(
                        resultSet.getString("demand_business_id"),
                        resultSet.getInt("required_quantity"),
                        resultSet.getInt("pending_work_quantity"),
                        resultSet.getInt("in_process_quantity"),
                        resultSet.getInt("target_ready_quantity"),
                        resultSet.getInt("fulfilled_quantity"),
                        resultSet.getInt("fulfillment_gap_quantity"),
                        resultSet.getLong("row_version")))
                .optional();
    }

    @Override
    public void insertDemandProjection(QuantityProjections.DemandProjection projection) {
        jdbc.sql("""
                        INSERT INTO production_demand_quantity_projections (
                            demand_business_id, required_quantity, pending_work_quantity,
                            in_process_quantity, target_ready_quantity, fulfilled_quantity,
                            fulfillment_gap_quantity, updated_at, row_version)
                        VALUES (:demandId, :required, :pending, :inProcess, :targetReady, :fulfilled,
                                :gap, UTC_TIMESTAMP(6), 1)
                        ON DUPLICATE KEY UPDATE
                            required_quantity = VALUES(required_quantity),
                            pending_work_quantity = VALUES(pending_work_quantity),
                            in_process_quantity = VALUES(in_process_quantity),
                            target_ready_quantity = VALUES(target_ready_quantity),
                            fulfilled_quantity = VALUES(fulfilled_quantity),
                            fulfillment_gap_quantity = VALUES(fulfillment_gap_quantity),
                            updated_at = VALUES(updated_at), row_version = row_version + 1
                        """)
                .param("demandId", projection.demandBusinessId())
                .param("required", projection.requiredQuantity())
                .param("pending", projection.pendingWorkQuantity())
                .param("inProcess", projection.inProcessQuantity())
                .param("targetReady", projection.targetReadyQuantity())
                .param("fulfilled", projection.fulfilledQuantity())
                .param("gap", projection.fulfillmentGapQuantity())
                .update();
    }

    @Override
    public Optional<QuantityProjections.PhysicalProjection> findPhysicalProjection(
            String physicalRootSourceBusinessId) {
        return jdbc.sql("""
                        SELECT physical_root_source_business_id, cumulative_making_created_quantity,
                               in_system_quantity, consumed_quantity, destroyed_quantity,
                               offline_inventory_quantity, row_version
                        FROM production_physical_quantity_projections
                        WHERE physical_root_source_business_id = :id
                        """)
                .param("id", physicalRootSourceBusinessId)
                .query((resultSet, rowNumber) -> new QuantityProjections.PhysicalProjection(
                        resultSet.getString("physical_root_source_business_id"),
                        resultSet.getInt("cumulative_making_created_quantity"),
                        resultSet.getInt("in_system_quantity"),
                        resultSet.getInt("consumed_quantity"),
                        resultSet.getInt("destroyed_quantity"),
                        resultSet.getInt("offline_inventory_quantity"),
                        resultSet.getLong("row_version")))
                .optional();
    }

    @Override
    public void insertPhysicalProjection(QuantityProjections.PhysicalProjection projection) {
        jdbc.sql("""
                        INSERT INTO production_physical_quantity_projections (
                            physical_root_source_business_id, cumulative_making_created_quantity,
                            in_system_quantity, consumed_quantity, destroyed_quantity,
                            offline_inventory_quantity, updated_at, row_version)
                        VALUES (:physicalRoot, :cumulative, :inSystem, :consumed, :destroyed,
                                :offline, UTC_TIMESTAMP(6), 1)
                        ON DUPLICATE KEY UPDATE
                            cumulative_making_created_quantity = VALUES(cumulative_making_created_quantity),
                            in_system_quantity = VALUES(in_system_quantity),
                            consumed_quantity = VALUES(consumed_quantity),
                            destroyed_quantity = VALUES(destroyed_quantity),
                            offline_inventory_quantity = VALUES(offline_inventory_quantity),
                            updated_at = VALUES(updated_at), row_version = row_version + 1
                        """)
                .param("physicalRoot", projection.physicalRootSourceBusinessId())
                .param("cumulative", projection.cumulativeMakingCreatedQuantity())
                .param("inSystem", projection.inSystemQuantity())
                .param("consumed", projection.consumedQuantity())
                .param("destroyed", projection.destroyedQuantity())
                .param("offline", projection.offlineInventoryQuantity())
                .update();
    }

    @Override
    public SourceRecord lockSource(String productionSourceBusinessId) {
        return jdbc.sql(SOURCE_SELECT + " WHERE production_source_business_id = :id FOR UPDATE")
                .param("id", productionSourceBusinessId)
                .query(JdbcProductionRepository::mapSource)
                .optional()
                .orElseThrow(() -> new IllegalStateException("生产来源不存在：" + productionSourceBusinessId));
    }

    @Override
    public long countDemands(SourceKind sourceKind) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM production_sources WHERE source_kind = :kind
                        """)
                .param("kind", sourceKind.name())
                .query(Long.class)
                .single();
    }

    @Override
    public void updateDemandRequiredQuantity(String demandBusinessId, int requiredQuantity) {
        jdbc.sql("""
                        UPDATE production_demands
                        SET required_quantity = :quantity, content_version = content_version + 1,
                            row_version = row_version + 1, updated_at = UTC_TIMESTAMP(6)
                        WHERE demand_business_id = :id
                        """)
                .param("quantity", requiredQuantity)
                .param("id", demandBusinessId)
                .update();
        if (requiredQuantity == 0) {
            jdbc.sql("""
                            UPDATE production_demands SET status = 'CANCELLED'
                            WHERE demand_business_id = :id AND required_quantity = 0
                            """)
                    .param("id", demandBusinessId)
                    .update();
        }
    }

    @Override
    public void insertDemandChangeFact(DemandChangeFact fact) {
        jdbc.sql("""
                        INSERT INTO production_demand_change_facts (
                            fact_business_id, demand_business_id, production_source_business_id,
                            upstream_change_business_id, change_type, quantity_before, quantity_after,
                            quantity_delta, cancel_unproduced_quantity, transfer_produced_quantity,
                            release_to_return_pool_quantity, release_repair_gap_quantity,
                            reason_code, reason_text, occurred_at, operator_id, is_valid)
                        VALUES (:factId, :demandId, :sourceId, NULL, :changeType, :before, :after,
                                :delta, :cancelUnproduced, :transferProduced, :releaseReturnPool,
                                :releaseRepairGap, :reasonCode, :reasonText, UTC_TIMESTAMP(6),
                                'SYSTEM', 1)
                        """)
                .param("factId", fact.factBusinessId())
                .param("demandId", fact.demandBusinessId())
                .param("sourceId", fact.productionSourceBusinessId())
                .param("changeType", fact.changeType())
                .param("before", fact.quantityBefore())
                .param("after", fact.quantityAfter())
                .param("delta", fact.quantityDelta())
                .param("cancelUnproduced", fact.cancelUnproducedQuantity())
                .param("transferProduced", fact.transferProducedQuantity())
                .param("releaseReturnPool", fact.releaseToReturnPoolQuantity())
                .param("releaseRepairGap", fact.releaseRepairGapQuantity())
                .param("reasonCode", fact.reasonCode())
                .param("reasonText", fact.reasonText())
                .update();
    }

    @Override
    public long countOpeningFactsOfSource(String productionSourceBusinessId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM production_opening_facts
                        WHERE production_source_business_id = :id AND is_valid = 1
                        """)
                .param("id", productionSourceBusinessId)
                .query(Long.class)
                .single();
    }

    @Override
    public void insertOpeningFact(OpeningFact fact) {
        jdbc.sql("""
                        INSERT INTO production_opening_facts (
                            fact_business_id, opening_batch_business_id, demand_business_id,
                            production_source_business_id, stage_code, quality_code, disposition_code,
                            physical_destination, rework_process_code, quantity, reason_code, reason_text,
                            occurred_at, operator_id, is_valid)
                        VALUES (:factId, :batchId, :demandId, :sourceId, :stageCode, :qualityCode,
                                :dispositionCode, :destination, :reworkProcess, :quantity, :reasonCode,
                                :reasonText, :occurredAt, :operatorId, 1)
                        """)
                .param("factId", fact.factBusinessId())
                .param("batchId", fact.openingBatchBusinessId())
                .param("demandId", fact.demandBusinessId())
                .param("sourceId", fact.productionSourceBusinessId())
                .param("stageCode", fact.stageCode())
                .param("qualityCode", fact.qualityCode())
                .param("dispositionCode", fact.dispositionCode())
                .param("destination", fact.physicalDestination())
                .param("reworkProcess", fact.reworkProcessCode())
                .param("quantity", fact.quantity())
                .param("reasonCode", fact.reasonCode())
                .param("reasonText", fact.reasonText())
                .param("occurredAt", JdbcTimes.toUtc(fact.occurredAt()))
                .param("operatorId", fact.operatorId())
                .update();
    }

    @Override
    public void updateSourceOpening(String productionSourceBusinessId, String openingStatus, String status) {
        jdbc.sql("""
                        UPDATE production_sources
                        SET opening_status = :openingStatus, status = :status,
                            row_version = row_version + 1
                        WHERE production_source_business_id = :id
                        """)
                .param("openingStatus", openingStatus)
                .param("status", status)
                .param("id", productionSourceBusinessId)
                .update();
    }

    @Override
    public void insertStageProjection(StageProjection projection) {
        jdbc.sql("""
                        INSERT INTO production_stage_quantity_projections (
                            production_source_business_id, physical_root_source_business_id, stage_code,
                            quality_code, disposition_code, physical_destination, available_quantity,
                            reserved_quantity, updated_at)
                        VALUES (:sourceId, :physicalRoot, :stageCode, :qualityCode, :dispositionCode,
                                :destination, :available, 0, UTC_TIMESTAMP(6))
                        ON DUPLICATE KEY UPDATE
                            available_quantity = available_quantity + :available,
                            updated_at = UTC_TIMESTAMP(6)
                        """)
                .param("sourceId", projection.productionSourceBusinessId())
                .param("physicalRoot", projection.physicalRootSourceBusinessId())
                .param("stageCode", projection.stageCode())
                .param("qualityCode", projection.qualityCode())
                .param("dispositionCode", projection.dispositionCode())
                .param("destination", projection.physicalDestination())
                .param("available", projection.availableQuantity())
                .update();
    }

    private static final String DEMAND_SELECT = """
            SELECT id, demand_business_id, demand_type, source_module, source_type, source_business_id,
                   order_business_id, order_item_business_id, product_business_id, product_snapshot_ref,
                   scheme_business_id, scheme_snapshot_ref, target_process,
                   basis_returned_source_business_id, production_basis_json,
                   production_basis_fingerprint, required_quantity, status, content_version,
                   row_version, created_at, updated_at
            FROM production_demands
            """;

    private static final String SOURCE_SELECT = """
            SELECT id, production_source_business_id, demand_business_id, source_kind,
                   physical_root_source_business_id, returned_source_business_id, product_business_id,
                   product_snapshot_ref, scheme_business_id, scheme_snapshot_ref,
                   production_basis_fingerprint, initial_quantity, target_process, opening_status,
                   status, row_version, created_at
            FROM production_sources
            """;

    private static DemandRecord mapDemand(ResultSet resultSet, int rowNumber) throws SQLException {
        return new DemandRecord(
                resultSet.getLong("id"),
                resultSet.getString("demand_business_id"),
                DemandType.valueOf(resultSet.getString("demand_type")),
                resultSet.getString("source_module"),
                resultSet.getString("source_type"),
                resultSet.getString("source_business_id"),
                resultSet.getString("order_business_id"),
                resultSet.getString("order_item_business_id"),
                resultSet.getString("product_business_id"),
                resultSet.getString("product_snapshot_ref"),
                resultSet.getString("scheme_business_id"),
                resultSet.getString("scheme_snapshot_ref"),
                TargetProcess.valueOf(resultSet.getString("target_process")),
                resultSet.getString("basis_returned_source_business_id"),
                resultSet.getString("production_basis_json"),
                resultSet.getString("production_basis_fingerprint"),
                resultSet.getInt("required_quantity"),
                DemandStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("content_version"),
                resultSet.getLong("row_version"),
                JdbcTimes.toInstant(resultSet.getObject("created_at", LocalDateTime.class)),
                JdbcTimes.toInstant(resultSet.getObject("updated_at", LocalDateTime.class)));
    }

    private static SourceRecord mapSource(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SourceRecord(
                resultSet.getLong("id"),
                resultSet.getString("production_source_business_id"),
                resultSet.getString("demand_business_id"),
                SourceKind.valueOf(resultSet.getString("source_kind")),
                resultSet.getString("physical_root_source_business_id"),
                resultSet.getString("returned_source_business_id"),
                resultSet.getString("product_business_id"),
                resultSet.getString("product_snapshot_ref"),
                resultSet.getString("scheme_business_id"),
                resultSet.getString("scheme_snapshot_ref"),
                resultSet.getString("production_basis_fingerprint"),
                resultSet.getInt("initial_quantity"),
                TargetProcess.valueOf(resultSet.getString("target_process")),
                resultSet.getString("opening_status"),
                resultSet.getString("status"),
                resultSet.getLong("row_version"),
                JdbcTimes.toInstant(resultSet.getObject("created_at", LocalDateTime.class)));
    }
}

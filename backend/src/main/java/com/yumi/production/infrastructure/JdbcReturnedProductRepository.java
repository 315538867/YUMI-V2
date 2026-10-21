package com.yumi.production.infrastructure;

import com.yumi.platform.api.jdbc.JdbcTimes;
import com.yumi.production.application.port.QuantityProjections;
import com.yumi.production.application.port.ReturnedProductRepository;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 售后退回制品池持久化（MySQL）。 */
@Repository
public class JdbcReturnedProductRepository implements ReturnedProductRepository {

    private final JdbcClient jdbc;

    public JdbcReturnedProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ReturnedSourceRecord> findReturnedSource(String returnedSourceBusinessId) {
        return queryReturnedSource(returnedSourceBusinessId, false);
    }

    @Override
    public Optional<ReturnedSourceRecord> lockReturnedSource(String returnedSourceBusinessId) {
        return queryReturnedSource(returnedSourceBusinessId, true);
    }

    @Override
    public Optional<ReturnedSourceRecord> findByReceiptItem(String receiptItemBusinessId) {
        return jdbc.sql("""
                        SELECT returned_source_business_id, after_sales_return_receipt_item_business_id,
                               physical_root_source_business_id, prior_delivery_source_type,
                               prior_production_source_business_id, prior_returned_source_business_id,
                               prior_consumption_fact_business_id, product_snapshot_ref,
                               scheme_snapshot_ref, returned_stage_code, original_quantity,
                               available_quantity, routed_repair_quantity, disposed_quantity,
                               status, row_version, created_at
                        FROM production_returned_product_sources
                        WHERE after_sales_return_receipt_item_business_id = :id
                        """)
                .param("id", receiptItemBusinessId)
                .query(JdbcReturnedProductRepository::mapReturnedSource)
                .optional();
    }

    private Optional<ReturnedSourceRecord> queryReturnedSource(String id, boolean lock) {
        return jdbc.sql("""
                        SELECT returned_source_business_id, after_sales_return_receipt_item_business_id,
                               physical_root_source_business_id, prior_delivery_source_type,
                               prior_production_source_business_id, prior_returned_source_business_id,
                               prior_consumption_fact_business_id, product_snapshot_ref,
                               scheme_snapshot_ref, returned_stage_code, original_quantity,
                               available_quantity, routed_repair_quantity, disposed_quantity,
                               status, row_version, created_at
                        FROM production_returned_product_sources
                        WHERE returned_source_business_id = :id
                        """ + (lock ? " FOR UPDATE" : ""))
                .param("id", id)
                .query(JdbcReturnedProductRepository::mapReturnedSource)
                .optional();
    }

    @Override
    public void insertReturnedSource(ReturnedSourceRecord record) {
        jdbc.sql("""
                        INSERT INTO production_returned_product_sources (
                            returned_source_business_id, after_sales_return_receipt_item_business_id,
                            physical_root_source_business_id, prior_delivery_source_type,
                            prior_production_source_business_id, prior_returned_source_business_id,
                            prior_consumption_fact_business_id, product_snapshot_ref,
                            scheme_snapshot_ref, returned_stage_code, original_quantity,
                            available_quantity, routed_repair_quantity, disposed_quantity,
                            status, row_version, created_at)
                        VALUES (:id, :receiptItemId, :physicalRoot, :priorSourceType,
                                :priorProductionSourceId, :priorReturnedSourceId, :priorConsumptionId,
                                :productSnapshot, :schemeSnapshot, :stageCode, :original, :available,
                                0, 0, :status, 1, :createdAt)
                        """)
                .param("id", record.returnedSourceBusinessId())
                .param("receiptItemId", record.afterSalesReturnReceiptItemBusinessId())
                .param("physicalRoot", record.physicalRootSourceBusinessId())
                .param("priorSourceType", record.priorDeliverySourceType())
                .param("priorProductionSourceId", record.priorProductionSourceBusinessId())
                .param("priorReturnedSourceId", record.priorReturnedSourceBusinessId())
                .param("priorConsumptionId", record.priorConsumptionFactBusinessId())
                .param("productSnapshot", record.productSnapshotRef())
                .param("schemeSnapshot", record.schemeSnapshotRef())
                .param("stageCode", record.returnedStageCode())
                .param("original", record.originalQuantity())
                .param("available", record.availableQuantity())
                .param("status", record.status())
                .param("createdAt", JdbcTimes.toUtc(record.createdAt()))
                .update();
    }

    @Override
    public void updateReturnedSourceQuantities(
            String returnedSourceBusinessId, int availableQuantity, int routedRepairQuantity,
            int disposedQuantity, String status) {
        jdbc.sql("""
                        UPDATE production_returned_product_sources
                        SET available_quantity = :available, routed_repair_quantity = :routed,
                            disposed_quantity = :disposed, status = :status,
                            row_version = row_version + 1
                        WHERE returned_source_business_id = :id
                        """)
                .param("available", availableQuantity)
                .param("routed", routedRepairQuantity)
                .param("disposed", disposedQuantity)
                .param("status", status)
                .param("id", returnedSourceBusinessId)
                .update();
    }

    @Override
    public void insertReentryFact(ReentryFact record) {
        jdbc.sql("""
                        INSERT INTO production_return_reentry_facts (
                            fact_business_id, returned_source_business_id,
                            after_sales_return_receipt_item_business_id,
                            physical_root_source_business_id, prior_delivery_source_type,
                            prior_production_source_business_id, prior_returned_source_business_id,
                            prior_consumption_fact_business_id, quantity, occurred_at, operator_id,
                            is_valid)
                        VALUES (:id, :returnedSourceId, :receiptItemId, :physicalRoot, :priorSourceType,
                                :priorProductionSourceId, :priorReturnedSourceId, :priorConsumptionId,
                                :quantity, :occurredAt, :operatorId, 1)
                        """)
                .param("id", record.factBusinessId())
                .param("returnedSourceId", record.returnedSourceBusinessId())
                .param("receiptItemId", record.afterSalesReturnReceiptItemBusinessId())
                .param("physicalRoot", record.physicalRootSourceBusinessId())
                .param("priorSourceType", record.priorDeliverySourceType())
                .param("priorProductionSourceId", record.priorProductionSourceBusinessId())
                .param("priorReturnedSourceId", record.priorReturnedSourceBusinessId())
                .param("priorConsumptionId", record.priorConsumptionFactBusinessId())
                .param("quantity", record.quantity())
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public QuantityProjections.PhysicalProjection lockPhysicalRoot(String physicalRootSourceBusinessId) {
        return jdbc.sql("""
                        SELECT physical_root_source_business_id, cumulative_making_created_quantity,
                               in_system_quantity, consumed_quantity, destroyed_quantity,
                               offline_inventory_quantity, row_version
                        FROM production_physical_quantity_projections
                        WHERE physical_root_source_business_id = :id FOR UPDATE
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
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "物理根投影不存在：" + physicalRootSourceBusinessId));
    }

    @Override
    public void insertPoolConsumption(PoolConsumption record) {
        jdbc.sql("""
                        INSERT INTO production_returned_pool_consumption_facts (
                            consumption_fact_business_id, returned_source_business_id,
                            physical_root_source_business_id, source_module, source_type,
                            source_business_id, quantity, occurred_at, operator_id, is_valid)
                        VALUES (:id, :returnedSourceId, :physicalRoot, :sourceModule, :sourceType,
                                :sourceBusinessId, :quantity, :occurredAt, :operatorId, 1)
                        """)
                .param("id", record.consumptionFactBusinessId())
                .param("returnedSourceId", record.returnedSourceBusinessId())
                .param("physicalRoot", record.physicalRootSourceBusinessId())
                .param("sourceModule", record.sourceModule())
                .param("sourceType", record.sourceType())
                .param("sourceBusinessId", record.sourceBusinessId())
                .param("quantity", record.quantity())
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public void insertPoolDisposition(PoolDisposition record) {
        jdbc.sql("""
                        INSERT INTO production_returned_product_disposition_facts (
                            fact_business_id, returned_source_business_id,
                            physical_root_source_business_id, disposition_type,
                            physical_destination, terminal_exit_business_id, quantity,
                            consumption_fact_business_id, offline_transfer_fact_business_id,
                            reason_code, reason_text, business_date, occurred_at, operator_id,
                            is_valid)
                        VALUES (:id, :returnedSourceId, :physicalRoot, :type, :destination, :exitId,
                                :quantity, :consumptionId, :transferId, :reasonCode, :reasonText,
                                :businessDate, :occurredAt, :operatorId, 1)
                        """)
                .param("id", record.factBusinessId())
                .param("returnedSourceId", record.returnedSourceBusinessId())
                .param("physicalRoot", record.physicalRootSourceBusinessId())
                .param("type", record.dispositionType().name())
                .param("destination", record.physicalDestination().name())
                .param("exitId", record.terminalExitBusinessId())
                .param("quantity", record.quantity())
                .param("consumptionId", record.consumptionFactBusinessId())
                .param("transferId", record.offlineTransferFactBusinessId())
                .param("reasonCode", record.reasonCode())
                .param("reasonText", record.reasonText())
                .param("businessDate", java.sql.Date.valueOf(record.businessDate()))
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public void insertPoolTransfer(PoolTransfer record) {
        jdbc.sql("""
                        INSERT INTO production_returned_pool_transfers (
                            transfer_fact_business_id, terminal_exit_business_id,
                            returned_source_business_id, physical_root_source_business_id,
                            disposition_fact_business_id, offline_inventory_name,
                            offline_inventory_note, handed_over_by, received_by, transferred_at,
                            quantity, occurred_at, operator_id, is_valid)
                        VALUES (:id, :exitId, :returnedSourceId, :physicalRoot, :dispositionId,
                                :inventoryName, :inventoryNote, :handedOverBy, :receivedBy,
                                :transferredAt, :quantity, :occurredAt, :operatorId, 1)
                        """)
                .param("id", record.transferFactBusinessId())
                .param("exitId", record.terminalExitBusinessId())
                .param("returnedSourceId", record.returnedSourceBusinessId())
                .param("physicalRoot", record.physicalRootSourceBusinessId())
                .param("dispositionId", record.dispositionFactBusinessId())
                .param("inventoryName", record.offlineInventoryName())
                .param("inventoryNote", record.offlineInventoryNote())
                .param("handedOverBy", record.handedOverBy())
                .param("receivedBy", record.receivedBy())
                .param("transferredAt", JdbcTimes.toUtc(
                        record.transferredAt().toInstant(java.time.ZoneOffset.UTC)))
                .param("quantity", record.quantity())
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public int countExecutionEvidence(String physicalRootSourceBusinessId, String subjectBusinessId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM production_execution_facts
                        WHERE physical_root_source_business_id = :root
                          AND subject_business_id = :subjectId AND is_valid = 1
                        """)
                .param("root", physicalRootSourceBusinessId)
                .param("subjectId", subjectBusinessId)
                .query(Integer.class)
                .single();
    }

    private static ReturnedSourceRecord mapReturnedSource(
            java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new ReturnedSourceRecord(
                resultSet.getString("returned_source_business_id"),
                resultSet.getString("after_sales_return_receipt_item_business_id"),
                resultSet.getString("physical_root_source_business_id"),
                resultSet.getString("prior_delivery_source_type"),
                resultSet.getString("prior_production_source_business_id"),
                resultSet.getString("prior_returned_source_business_id"),
                resultSet.getString("prior_consumption_fact_business_id"),
                resultSet.getString("product_snapshot_ref"),
                resultSet.getString("scheme_snapshot_ref"),
                resultSet.getString("returned_stage_code"),
                resultSet.getInt("original_quantity"),
                resultSet.getInt("available_quantity"),
                resultSet.getInt("routed_repair_quantity"),
                resultSet.getInt("disposed_quantity"),
                resultSet.getString("status"),
                resultSet.getLong("row_version"),
                JdbcTimes.toInstant(resultSet.getObject("created_at", java.time.LocalDateTime.class)));
    }
}

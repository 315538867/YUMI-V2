package com.yumi.production.infrastructure;

import com.yumi.platform.api.jdbc.JdbcTimes;
import com.yumi.production.application.port.ConsumptionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 消费与线下移交持久化（MySQL）。 */
@Repository
public class JdbcConsumptionRepository implements ConsumptionRepository {

    private final JdbcClient jdbc;

    public JdbcConsumptionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockSource(String productionSourceBusinessId) {
        jdbc.sql("""
                        SELECT production_source_business_id FROM production_sources
                        WHERE production_source_business_id = :id FOR UPDATE
                        """)
                .param("id", productionSourceBusinessId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("生产来源不存在：" + productionSourceBusinessId));
    }

    @Override
    public int consumedQuantityOf(String productionSourceBusinessId) {
        // 消费与反向都是永久保留的事实：有效净消费 = CONSUME − REVERSE。
        return jdbc.sql("""
                        SELECT COALESCE(SUM(
                                   CASE WHEN effect_type = 'CONSUME' THEN quantity ELSE -quantity END), 0)
                        FROM production_consumption_facts
                        WHERE production_source_business_id = :id AND is_valid = 1
                        """)
                .param("id", productionSourceBusinessId)
                .query(Integer.class)
                .single();
    }

    @Override
    public int transferredQuantityOf(String productionSourceBusinessId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(quantity), 0) FROM production_offline_inventory_transfers
                        WHERE production_source_business_id = :id AND is_valid = 1
                        """)
                .param("id", productionSourceBusinessId)
                .query(Integer.class)
                .single();
    }

    @Override
    public int activeReservationOf(String productionSourceBusinessId) {
        return jdbc.sql("""
                        SELECT COALESCE(reserved_quantity, 0) FROM production_source_reservations
                        WHERE production_source_business_id = :id
                        """)
                .param("id", productionSourceBusinessId)
                .query(Integer.class)
                .single();
    }

    @Override
    public List<ConsumptionRecord> findConsumptions(
            String sourceModule, String sourceType, String sourceBusinessId) {
        return jdbc.sql("""
                        SELECT consumption_fact_business_id, effect_type, reverses_fact_business_id,
                               source_module, source_type, source_business_id,
                               production_source_business_id, physical_root_source_business_id,
                               demand_business_id, product_business_id, quantity, occurred_at, operator_id
                        FROM production_consumption_facts
                        WHERE source_module = :sourceModule AND source_type = :sourceType
                          AND source_business_id = :sourceBusinessId AND is_valid = 1
                        ORDER BY occurred_at, consumption_fact_business_id
                        """)
                .param("sourceModule", sourceModule)
                .param("sourceType", sourceType)
                .param("sourceBusinessId", sourceBusinessId)
                .query(JdbcConsumptionRepository::mapConsumption)
                .list();
    }

    @Override
    public int reversedQuantityOf(String consumptionFactBusinessId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(quantity), 0) FROM production_consumption_facts
                        WHERE reverses_fact_business_id = :id AND effect_type = 'REVERSE' AND is_valid = 1
                        """)
                .param("id", consumptionFactBusinessId)
                .query(Integer.class)
                .single();
    }

    @Override
    public void insertConsumption(ConsumptionRecord record) {
        jdbc.sql("""
                        INSERT INTO production_consumption_facts (
                            consumption_fact_business_id, effect_type, reverses_fact_business_id,
                            source_module, source_type, source_business_id,
                            production_source_business_id, physical_root_source_business_id,
                            demand_business_id, product_business_id, quantity, occurred_at,
                            operator_id, is_valid)
                        VALUES (:id, :effectType, :reversesId, :sourceModule, :sourceType, :sourceBusinessId,
                                :productionSourceId, :physicalRoot, :demandId, :productId, :quantity,
                                :occurredAt, :operatorId, 1)
                        """)
                .param("id", record.consumptionFactBusinessId())
                .param("effectType", record.effectType())
                .param("reversesId", record.reversesFactBusinessId())
                .param("sourceModule", record.sourceModule())
                .param("sourceType", record.sourceType())
                .param("sourceBusinessId", record.sourceBusinessId())
                .param("productionSourceId", record.productionSourceBusinessId())
                .param("physicalRoot", record.physicalRootSourceBusinessId())
                .param("demandId", record.demandBusinessId())
                .param("productId", record.productBusinessId())
                .param("quantity", record.quantity())
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public void insertTransfer(TransferRecord record) {
        jdbc.sql("""
                        INSERT INTO production_offline_inventory_transfers (
                            transfer_fact_business_id, terminal_exit_business_id,
                            production_source_business_id, physical_root_source_business_id,
                            demand_business_id, product_business_id, reason_code,
                            upstream_change_business_id, offline_inventory_name, offline_inventory_note,
                            handed_over_by, received_by, transferred_at, quantity, occurred_at,
                            operator_id, is_valid)
                        VALUES (:id, :exitId, :productionSourceId, :physicalRoot, :demandId, :productId,
                                :reasonCode, :upstreamChangeId, :inventoryName, :inventoryNote,
                                :handedOverBy, :receivedBy, :transferredAt, :quantity, :occurredAt,
                                :operatorId, 1)
                        """)
                .param("id", record.transferFactBusinessId())
                .param("exitId", record.terminalExitBusinessId())
                .param("productionSourceId", record.productionSourceBusinessId())
                .param("physicalRoot", record.physicalRootSourceBusinessId())
                .param("demandId", record.demandBusinessId())
                .param("productId", record.productBusinessId())
                .param("reasonCode", record.reasonCode())
                .param("upstreamChangeId", record.upstreamChangeBusinessId())
                .param("inventoryName", record.offlineInventoryName())
                .param("inventoryNote", record.offlineInventoryNote())
                .param("handedOverBy", record.handedOverBy())
                .param("receivedBy", record.receivedBy())
                .param("transferredAt", JdbcTimes.toUtc(record.transferredAt()))
                .param("quantity", record.quantity())
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public Optional<TransferRecord> findTransfer(String transferFactBusinessId) {
        return jdbc.sql("""
                        SELECT transfer_fact_business_id, terminal_exit_business_id,
                               production_source_business_id, physical_root_source_business_id,
                               demand_business_id, product_business_id, reason_code,
                               upstream_change_business_id, offline_inventory_name, offline_inventory_note,
                               handed_over_by, received_by, transferred_at, quantity, occurred_at, operator_id
                        FROM production_offline_inventory_transfers
                        WHERE transfer_fact_business_id = :id
                        """)
                .param("id", transferFactBusinessId)
                .query((resultSet, rowNumber) -> new TransferRecord(
                        resultSet.getString("transfer_fact_business_id"),
                        resultSet.getString("terminal_exit_business_id"),
                        resultSet.getString("production_source_business_id"),
                        resultSet.getString("physical_root_source_business_id"),
                        resultSet.getString("demand_business_id"),
                        resultSet.getString("product_business_id"),
                        resultSet.getString("reason_code"),
                        resultSet.getString("upstream_change_business_id"),
                        resultSet.getString("offline_inventory_name"),
                        resultSet.getString("offline_inventory_note"),
                        resultSet.getString("handed_over_by"),
                        resultSet.getString("received_by"),
                        JdbcTimes.toInstant(resultSet.getObject("transferred_at", java.time.LocalDateTime.class)),
                        resultSet.getInt("quantity"),
                        JdbcTimes.toInstant(resultSet.getObject("occurred_at", java.time.LocalDateTime.class)),
                        resultSet.getString("operator_id")))
                .optional();
    }

    @Override
    public List<ConsumptionRecord> listConsumptions(String productionSourceBusinessId) {
        return jdbc.sql("""
                        SELECT consumption_fact_business_id, effect_type, reverses_fact_business_id,
                               source_module, source_type, source_business_id,
                               production_source_business_id, physical_root_source_business_id,
                               demand_business_id, product_business_id, quantity, occurred_at, operator_id
                        FROM production_consumption_facts
                        WHERE production_source_business_id = :id AND is_valid = 1
                        ORDER BY occurred_at
                        """)
                .param("id", productionSourceBusinessId)
                .query(JdbcConsumptionRepository::mapConsumption)
                .list();
    }

    private static ConsumptionRecord mapConsumption(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        return new ConsumptionRecord(
                resultSet.getString("consumption_fact_business_id"),
                resultSet.getString("effect_type"),
                resultSet.getString("reverses_fact_business_id"),
                resultSet.getString("source_module"),
                resultSet.getString("source_type"),
                resultSet.getString("source_business_id"),
                resultSet.getString("production_source_business_id"),
                resultSet.getString("physical_root_source_business_id"),
                resultSet.getString("demand_business_id"),
                resultSet.getString("product_business_id"),
                resultSet.getInt("quantity"),
                JdbcTimes.toInstant(resultSet.getObject("occurred_at", java.time.LocalDateTime.class)),
                resultSet.getString("operator_id"));
    }
}

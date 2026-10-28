package com.yumi.production.infrastructure;

import com.yumi.platform.api.jdbc.JdbcTimes;
import com.yumi.production.api.DemandType;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.application.port.RepairSourceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 返工来源持久化（MySQL）。 */
@Repository
public class JdbcRepairSourceRepository implements RepairSourceRepository {

    private static final String REPAIR_SELECT = """
            SELECT repair_source_business_id, origin_type, demand_business_id,
                   production_source_business_id, physical_root_source_business_id,
                   repair_process_code, original_quantity, available_quantity, reserved_quantity,
                   completed_quantity, status
            FROM production_repair_sources
            """;

    private final JdbcClient jdbc;

    public JdbcRepairSourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AvailableRepairSourceRecord> listAvailable() {
        return jdbc.sql("""
                        SELECT r.repair_source_business_id, r.origin_type, r.demand_business_id,
                               r.production_source_business_id, d.demand_type,
                               s.product_business_id, s.scheme_business_id, s.target_process,
                               r.repair_process_code, r.original_quantity, r.available_quantity,
                               r.reserved_quantity, r.completed_quantity
                        FROM production_repair_sources r
                        JOIN production_demands d ON d.demand_business_id = r.demand_business_id
                        JOIN production_sources s
                          ON s.production_source_business_id = r.production_source_business_id
                        WHERE r.status = 'ACTIVE'
                          AND r.available_quantity - r.reserved_quantity > 0
                        ORDER BY r.created_at, r.repair_source_business_id
                        """)
                .query((resultSet, rowNumber) -> new AvailableRepairSourceRecord(
                        resultSet.getString("repair_source_business_id"),
                        resultSet.getString("origin_type"),
                        resultSet.getString("demand_business_id"),
                        resultSet.getString("production_source_business_id"),
                        DemandType.valueOf(resultSet.getString("demand_type")),
                        resultSet.getString("product_business_id"),
                        resultSet.getString("scheme_business_id"),
                        TargetProcess.valueOf(resultSet.getString("target_process")),
                        TargetProcess.valueOf(resultSet.getString("repair_process_code")),
                        resultSet.getInt("original_quantity"),
                        resultSet.getInt("available_quantity"),
                        resultSet.getInt("reserved_quantity"),
                        resultSet.getInt("completed_quantity")))
                .list();
    }

    @Override
    public Optional<RepairSourceRecord> find(String repairSourceBusinessId) {
        return jdbc.sql(REPAIR_SELECT + " WHERE repair_source_business_id = :id")
                .param("id", repairSourceBusinessId)
                .query(JdbcRepairSourceRepository::map)
                .optional();
    }

    @Override
    public RepairSourceRecord lock(String repairSourceBusinessId) {
        return jdbc.sql(REPAIR_SELECT + " WHERE repair_source_business_id = :id FOR UPDATE")
                .param("id", repairSourceBusinessId)
                .query(JdbcRepairSourceRepository::map)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "返工来源不存在：" + repairSourceBusinessId));
    }

    @Override
    public void reserve(String repairSourceBusinessId, int quantity) {
        int updated = jdbc.sql("""
                        UPDATE production_repair_sources
                        SET reserved_quantity = reserved_quantity + :quantity,
                            row_version = row_version + 1
                        WHERE repair_source_business_id = :id
                        """)
                .param("quantity", quantity)
                .param("id", repairSourceBusinessId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("返工来源不存在：" + repairSourceBusinessId);
        }
    }

    @Override
    public void release(String repairSourceBusinessId, int quantity) {
        int updated = jdbc.sql("""
                        UPDATE production_repair_sources
                        SET reserved_quantity = reserved_quantity - :quantity,
                            row_version = row_version + 1
                        WHERE repair_source_business_id = :id
                          AND reserved_quantity >= :quantity
                        """)
                .param("quantity", quantity)
                .param("id", repairSourceBusinessId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException(
                    "返工来源预留不守恒：释放 " + quantity + " 超过当前预留 " + repairSourceBusinessId);
        }
    }

    @Override
    public void complete(
            String repairSourceBusinessId, int plannedQuantity, int completedQuantity) {
        int updated = jdbc.sql("""
                        UPDATE production_repair_sources
                        SET status = CASE
                                WHEN available_quantity - :completed = 0
                                 AND reserved_quantity - :planned = 0 THEN 'COMPLETED'
                                ELSE status END,
                            reserved_quantity = reserved_quantity - :planned,
                            completed_quantity = completed_quantity + :completed,
                            available_quantity = available_quantity - :completed,
                            row_version = row_version + 1
                        WHERE repair_source_business_id = :id
                          AND reserved_quantity >= :planned
                          AND available_quantity >= :completed
                        """)
                .param("planned", plannedQuantity)
                .param("completed", completedQuantity)
                .param("id", repairSourceBusinessId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException(
                    "返工来源核验结账不守恒：" + repairSourceBusinessId);
        }
    }

    @Override
    public void recordFact(
            String repairSourceBusinessId, String operationType, int quantity,
            String taskBusinessId, String taskItemBusinessId, Instant occurredAt, String operatorId) {
        jdbc.sql("""
                        INSERT INTO production_repair_source_facts (
                            fact_business_id, repair_source_business_id, operation_type, quantity,
                            task_business_id, task_item_business_id, occurred_at, operator_id, is_valid)
                        VALUES (:factId, :sourceId, :operationType, :quantity, :taskId, :itemId,
                                :occurredAt, :operatorId, 1)
                        """)
                .param("factId", "RSF-" + UUID.randomUUID())
                .param("sourceId", repairSourceBusinessId)
                .param("operationType", operationType)
                .param("quantity", quantity)
                .param("taskId", taskBusinessId)
                .param("itemId", taskItemBusinessId)
                .param("occurredAt", JdbcTimes.toUtc(occurredAt))
                .param("operatorId", operatorId)
                .update();
    }

    private static RepairSourceRecord map(
            java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new RepairSourceRecord(
                resultSet.getString("repair_source_business_id"),
                resultSet.getString("origin_type"),
                resultSet.getString("demand_business_id"),
                resultSet.getString("production_source_business_id"),
                resultSet.getString("physical_root_source_business_id"),
                resultSet.getString("repair_process_code"),
                resultSet.getInt("original_quantity"),
                resultSet.getInt("available_quantity"),
                resultSet.getInt("reserved_quantity"),
                resultSet.getInt("completed_quantity"),
                resultSet.getString("status"));
    }
}

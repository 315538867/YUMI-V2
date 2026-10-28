package com.yumi.production.infrastructure;

import com.yumi.production.api.SubjectType;
import com.yumi.production.application.port.SourceReservationRepository;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 活动任务来源占用持久化（MySQL）。 */
@Repository
public class JdbcSourceReservationRepository implements SourceReservationRepository {

    private final JdbcClient jdbc;

    public JdbcSourceReservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int lockReservedQuantity(String productionSourceBusinessId) {
        jdbc.sql("""
                        INSERT INTO production_source_reservations (
                            production_source_business_id, reserved_quantity, row_version)
                        VALUES (:sourceId, 0, 1)
                        ON DUPLICATE KEY UPDATE row_version = row_version
                        """)
                .param("sourceId", productionSourceBusinessId)
                .update();
        return jdbc.sql("""
                        SELECT reserved_quantity FROM production_source_reservations
                        WHERE production_source_business_id = :sourceId
                        FOR UPDATE
                        """)
                .param("sourceId", productionSourceBusinessId)
                .query(Integer.class)
                .single();
    }

    @Override
    public void reserve(String productionSourceBusinessId, int quantity) {
        int updated = jdbc.sql("""
                        UPDATE production_source_reservations
                        SET reserved_quantity = reserved_quantity + :quantity,
                            row_version = row_version + 1
                        WHERE production_source_business_id = :sourceId
                        """)
                .param("quantity", quantity)
                .param("sourceId", productionSourceBusinessId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("来源预留行不存在：" + productionSourceBusinessId);
        }
    }

    @Override
    public void release(String productionSourceBusinessId, int quantity) {
        int updated = jdbc.sql("""
                        UPDATE production_source_reservations
                        SET reserved_quantity = reserved_quantity - :quantity,
                            row_version = row_version + 1
                        WHERE production_source_business_id = :sourceId
                          AND reserved_quantity >= :quantity
                        """)
                .param("quantity", quantity)
                .param("sourceId", productionSourceBusinessId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException(
                    "来源预留不守恒：释放 " + quantity + " 超过当前预留 " + productionSourceBusinessId);
        }
    }

    @Override
    public Optional<SourceHold> findHold(String taskItemBusinessId) {
        return jdbc.sql("""
                        SELECT task_item_business_id, task_business_id, production_source_business_id,
                               repair_source_business_id, reserved_quantity, status
                        FROM production_source_holds WHERE task_item_business_id = :itemId
                        """)
                .param("itemId", taskItemBusinessId)
                .query((resultSet, rowNumber) -> new SourceHold(
                        resultSet.getString("task_business_id"),
                        resultSet.getString("task_item_business_id"),
                        resultSet.getString("production_source_business_id"),
                        resultSet.getString("repair_source_business_id"),
                        resultSet.getInt("reserved_quantity"),
                        resultSet.getString("status")))
                .optional();
    }

    @Override
    public void upsertActiveHold(SourceHold hold) {
        jdbc.sql("""
                        INSERT INTO production_source_holds (
                            task_item_business_id, task_business_id, production_source_business_id,
                            repair_source_business_id, reserved_quantity, status, reserved_at,
                            row_version)
                        VALUES (:itemId, :taskId, :sourceId, :repairSourceId, :quantity, 'ACTIVE',
                                UTC_TIMESTAMP(6), 1)
                        ON DUPLICATE KEY UPDATE
                            production_source_business_id = :sourceId,
                            repair_source_business_id = :repairSourceId,
                            reserved_quantity = :quantity, status = 'ACTIVE',
                            reserved_at = UTC_TIMESTAMP(6), released_at = NULL,
                            row_version = row_version + 1
                        """)
                .param("itemId", hold.taskItemBusinessId())
                .param("taskId", hold.taskBusinessId())
                .param("sourceId", hold.productionSourceBusinessId())
                .param("repairSourceId", hold.repairSourceBusinessId())
                .param("quantity", hold.quantity())
                .update();
    }

    @Override
    public void releaseHold(String taskItemBusinessId) {
        jdbc.sql("""
                        UPDATE production_source_holds
                        SET status = 'RELEASED', released_at = UTC_TIMESTAMP(6),
                            row_version = row_version + 1
                        WHERE task_item_business_id = :itemId AND status = 'ACTIVE'
                        """)
                .param("itemId", taskItemBusinessId)
                .update();
    }

    @Override
    public void releaseSubjectReferences(
            String taskBusinessId, SubjectType subjectType, String subjectBusinessId, LocalDate workDate) {
        jdbc.sql("""
                        UPDATE production_active_participant_references SET status = 'RELEASED'
                        WHERE task_business_id = :taskId
                        """)
                .param("taskId", taskBusinessId)
                .update();
        jdbc.sql("""
                        UPDATE production_active_subject_references SET status = 'RELEASED'
                        WHERE task_business_id = :taskId
                        """)
                .param("taskId", taskBusinessId)
                .update();
    }
}

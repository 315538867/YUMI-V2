package com.yumi.production.infrastructure;

import com.yumi.production.api.TaskStatus;
import com.yumi.production.application.port.ReductionRepository;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 任务缩减持久化（MySQL）。 */
@Repository
public class JdbcReductionRepository implements ReductionRepository {

    private final JdbcClient jdbc;

    public JdbcReductionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PendingItem> findPendingItems(String productionSourceBusinessId) {
        return jdbc.sql("""
                        SELECT task_item_business_id, task_business_id, planned_quantity,
                               item_standard_minutes, product_business_id, demand_type
                        FROM production_task_items
                        WHERE production_source_business_id = :id AND verification_status = 'PENDING'
                        ORDER BY task_business_id, item_order
                        """)
                .param("id", productionSourceBusinessId)
                .query((resultSet, rowNumber) -> new PendingItem(
                        resultSet.getString("task_item_business_id"),
                        resultSet.getString("task_business_id"),
                        resultSet.getInt("planned_quantity"),
                        resultSet.getInt("item_standard_minutes"),
                        resultSet.getString("product_business_id"),
                        resultSet.getString("demand_type")))
                .list();
    }

    @Override
    public void updateItemPlannedQuantity(
            String taskItemBusinessId, int plannedQuantity, int cancelledQuantity) {
        if (plannedQuantity <= 0) {
            jdbc.sql("""
                            UPDATE production_task_items
                            SET planned_quantity = 0, cancelled_quantity = :cancelled,
                                verification_status = 'CANCELLED', row_version = row_version + 1
                            WHERE task_item_business_id = :id
                            """)
                    .param("cancelled", cancelledQuantity)
                    .param("id", taskItemBusinessId)
                    .update();
            return;
        }
        jdbc.sql("""
                        UPDATE production_task_items
                        SET planned_quantity = :planned, cancelled_quantity = :cancelled,
                            row_version = row_version + 1
                        WHERE task_item_business_id = :id
                        """)
                .param("planned", plannedQuantity)
                .param("cancelled", cancelledQuantity)
                .param("id", taskItemBusinessId)
                .update();
    }

    @Override
    public void updateTaskTotals(String taskBusinessId, int plannedQuantity, long standardSeconds) {
        if (plannedQuantity <= 0) {
            cancelTask(taskBusinessId);
            return;
        }
        jdbc.sql("""
                        UPDATE production_tasks
                        SET planned_work_quantity = :planned, task_standard_seconds = :seconds,
                            row_version = row_version + 1, updated_at = UTC_TIMESTAMP(6)
                        WHERE task_business_id = :id
                        """)
                .param("planned", plannedQuantity)
                .param("seconds", standardSeconds)
                .param("id", taskBusinessId)
                .update();
    }

    @Override
    public void cancelTask(String taskBusinessId) {
        jdbc.sql("""
                        UPDATE production_tasks
                        SET planned_work_quantity = 0, task_standard_seconds = 0, status = :status,
                            row_version = row_version + 1, updated_at = UTC_TIMESTAMP(6)
                        WHERE task_business_id = :id
                        """)
                .param("status", TaskStatus.CANCELLED.name())
                .param("id", taskBusinessId)
                .update();
        jdbc.sql("""
                        INSERT INTO production_task_cancellation_facts (
                            fact_business_id, task_business_id, reason_code, reason_text, occurred_at,
                            operator_id, is_valid)
                        VALUES (:factId, :taskId, 'ORDER_REDUCTION', '订单减量取消任务',
                                UTC_TIMESTAMP(6), 'SYSTEM', 1)
                        """)
                .param("factId", "TCF-" + java.util.UUID.randomUUID())
                .param("taskId", taskBusinessId)
                .update();
    }

    @Override
    public void releaseReservation(String productionSourceBusinessId, int quantity) {
        jdbc.sql("""
                        UPDATE production_source_reservations
                        SET reserved_quantity = GREATEST(reserved_quantity - :quantity, 0),
                            row_version = row_version + 1
                        WHERE production_source_business_id = :id
                        """)
                .param("quantity", quantity)
                .param("id", productionSourceBusinessId)
                .update();
    }

    @Override
    public void releaseHold(String taskItemBusinessId, int remainingQuantity) {
        if (remainingQuantity <= 0) {
            jdbc.sql("""
                            UPDATE production_source_holds
                            SET status = 'RELEASED', released_at = UTC_TIMESTAMP(6),
                                row_version = row_version + 1
                            WHERE task_item_business_id = :id
                            """)
                    .param("id", taskItemBusinessId)
                    .update();
            return;
        }
        jdbc.sql("""
                        UPDATE production_source_holds
                        SET reserved_quantity = :quantity, row_version = row_version + 1
                        WHERE task_item_business_id = :id
                        """)
                .param("quantity", remainingQuantity)
                .param("id", taskItemBusinessId)
                .update();
    }
}

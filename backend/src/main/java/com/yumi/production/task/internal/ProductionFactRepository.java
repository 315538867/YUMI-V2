package com.yumi.production.task.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

/**
 * 任务事实时间线读取（阶段五 5.18）：把计划、核验、返工事实与来源、报废、数量回转、
 * 库存接入、合格流转、未完成、取消、超额预占、计划调整提醒与工时更正聚合成同一时间线。
 * 只读；每类事实都保留来源 id、父核验/父来源、操作人、原因与数量。
 */
@Repository
public class ProductionFactRepository {

    private static final String SQL = """
            SELECT i.created_at fact_time, 'PLAN' fact_type, i.id fact_id, i.node node,
                   i.planned_quantity quantity, i.order_item_id order_item_id, NULL reference_id,
                   i.created_by operator, NULL reason, i.source_type note
            FROM production_task_items i WHERE i.task_id = ?
            UNION ALL
            SELECT v.verified_at, 'VERIFICATION', v.id, v.node, v.completed_quantity, v.order_item_id, v.id,
                   v.verified_by, NULL, CONCAT('合格 ', v.qualified_quantity, ' / 返工 ', v.rework_quantity,
                       ' / 报废 ', v.scrap_quantity, ' / 未完成 ', v.incomplete_quantity)
            FROM production_verifications v WHERE v.task_id = ?
            UNION ALL
            SELECT v.verified_at, 'REWORK_FACT', v.id, v.node, v.rework_quantity, v.order_item_id, v.id,
                   v.verified_by, NULL, '核验产生返工事实'
            FROM production_verifications v WHERE v.task_id = ? AND v.rework_quantity > 0
            UNION ALL
            SELECT s.created_at, 'REWORK_SOURCE', s.id, s.node, s.total_quantity, s.order_item_id,
                   s.previous_source_id, s.created_by, s.reason,
                   CONCAT('第 ', s.round_no, ' 轮，已安排 ', s.arranged_quantity, ' / ', s.total_quantity)
            FROM rework_sources s
            JOIN production_task_items i ON i.id = s.origin_task_item_id WHERE i.task_id = ?
            UNION ALL
            SELECT sc.recorded_at, 'SCRAP', sc.id, sc.node, sc.scrap_quantity, sc.order_item_id, sc.id,
                   sc.operator_username, sc.reason, '报废事实'
            FROM scrap_records sc
            JOIN production_task_items i ON i.id = sc.task_item_id WHERE i.task_id = ?
            UNION ALL
            SELECT r.created_at, 'QUANTITY_RETURN', r.id, r.node, r.returned_quantity, r.order_item_id,
                   r.scrap_record_id, r.created_by, NULL,
                   CONCAT('已分配 ', r.allocated_quantity, ' / ', r.returned_quantity)
            FROM production_quantity_returns r
            JOIN scrap_records sc ON sc.id = r.scrap_record_id
            JOIN production_task_items i ON i.id = sc.task_item_id WHERE i.task_id = ?
            UNION ALL
            SELECT e.created_at, 'INVENTORY_INFLOW', e.id, e.node, e.quantity, e.order_item_id, e.source_id,
                   e.operator_username, NULL, e.note
            FROM fulfillment_entries e
            JOIN production_task_items i ON i.order_item_id = e.order_item_id
            WHERE i.task_id = ? AND e.entry_type = 'INVENTORY_ALLOCATION' AND e.direction = 'IN'
            UNION ALL
            SELECT e.created_at, 'QUALIFIED_FLOW', e.id, e.node, e.quantity, e.order_item_id, e.source_id,
                   e.operator_username, NULL, e.note
            FROM fulfillment_entries e
            JOIN production_task_items i ON i.id = e.source_id
            WHERE i.task_id = ? AND e.entry_type = 'PRODUCTION_QUALIFIED' AND e.direction = 'IN'
            UNION ALL
            SELECT rm.created_at, 'INCOMPLETE', rm.id, rm.node, rm.quantity, rm.order_item_id, rm.verification_id,
                   rm.created_by, rm.reason, CONCAT('未完成提醒 ', rm.status)
            FROM production_reminders rm
            JOIN production_task_items i ON i.id = rm.task_item_id
            WHERE i.task_id = ? AND rm.reminder_type = 'INCOMPLETE'
            UNION ALL
            SELECT i.cancelled_at, 'CANCEL', i.id, i.node, i.planned_quantity, i.order_item_id, NULL,
                   i.cancelled_by, i.cancel_reason, '明细取消'
            FROM production_task_items i WHERE i.task_id = ? AND i.cancelled_at IS NOT NULL
            UNION ALL
            SELECT p.created_at, 'OVERTIME_PREEMPTION', p.id, p.node, p.preempted_quantity, p.order_item_id,
                   p.overtime_task_item_id, p.created_by, p.release_reason, CONCAT('预占 ', p.status)
            FROM overtime_preemptions p
            JOIN production_task_items i ON i.id = p.future_task_item_id WHERE i.task_id = ?
            UNION ALL
            SELECT rm.created_at, 'REMINDER', rm.id, rm.node, rm.quantity, rm.order_item_id, rm.preemption_id,
                   rm.created_by, rm.reason, CONCAT('计划调整提醒 ', rm.status)
            FROM production_reminders rm
            JOIN production_task_items i ON i.id = rm.future_task_item_id
            WHERE i.task_id = ? AND rm.reminder_type = 'PLAN_ADJUSTMENT'
            UNION ALL
            SELECT c.created_at, 'TIME_CORRECTION', c.id, NULL, c.after_total_minutes, NULL, c.schedule_id,
                   c.created_by, c.reason, CONCAT('有效分钟 ', c.before_total_minutes, ' → ',
                       c.after_total_minutes)
            FROM other_schedule_time_corrections c
            JOIN other_schedules s ON s.id = c.schedule_id
            JOIN production_tasks t ON t.id = ?
            WHERE s.employee_id = t.employee_id AND s.schedule_date = t.task_date
            ORDER BY fact_time, fact_type, fact_id
            """;

    private static final RowMapper<ProductionFactRow> MAPPER = ProductionFactRepository::map;

    private final JdbcTemplate jdbcTemplate;

    public ProductionFactRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ProductionFactRow> findByTask(long taskId) {
        return jdbcTemplate.query(SQL, MAPPER, java.util.Collections.nCopies(13, taskId).toArray());
    }

    private static ProductionFactRow map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp factTime = rs.getTimestamp("fact_time");
        return new ProductionFactRow(factTime == null ? null : factTime.toLocalDateTime(),
                rs.getString("fact_type"), rs.getLong("fact_id"), rs.getString("node"),
                nullableInt(rs, "quantity"), nullableLong(rs, "order_item_id"),
                nullableLong(rs, "reference_id"), rs.getString("operator"), rs.getString("reason"),
                rs.getString("note"));
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}

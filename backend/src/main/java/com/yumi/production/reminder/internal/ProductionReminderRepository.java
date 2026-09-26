package com.yumi.production.reminder.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 工作台提醒访问（阶段五 5.13）：提醒只辅助工作台，数量事实仍在任务明细、核验、来源与预占上。
 * 未完成提醒与计划待调整提醒都必须能从事实重建。
 */
@Repository
public class ProductionReminderRepository {

    public static final String TYPE_INCOMPLETE = "INCOMPLETE";
    public static final String TYPE_OVERTIME_PENDING_VERIFY = "OVERTIME_PENDING_VERIFY";
    public static final String TYPE_PLAN_ADJUSTMENT = "PLAN_ADJUSTMENT";
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_HANDLED = "HANDLED";

    private static final String COLUMNS = """
            id, reminder_type, order_id, order_item_id, node, task_item_id, verification_id, preemption_id,
            future_task_item_id, quantity, status, handling_type, handled_quantity, reason, handled_by,
            handled_at
            """;

    private static final RowMapper<ProductionReminderRow> MAPPER = ProductionReminderRepository::map;

    private final JdbcTemplate jdbcTemplate;

    public ProductionReminderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 核验未完成：正常明细的未完成部分回到普通待安排，并生成待处理提醒。 */
    public long insertIncomplete(long orderId, long orderItemId, String node, long taskItemId,
                                 long verificationId, int quantity, String requestId, String idempotencyKey) {
        jdbcTemplate.update("""
                INSERT INTO production_reminders (reminder_type, order_id, order_item_id, node, task_item_id,
                    verification_id, quantity, status, created_at, updated_at, request_id, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, ?)
                """, TYPE_INCOMPLETE, orderId, orderItemId, node, taskItemId, verificationId, quantity,
                requestId, idempotencyKey);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM production_reminders WHERE reminder_type = ? AND verification_id = ?
                """, Long.class, TYPE_INCOMPLETE, verificationId);
    }

    public Optional<ProductionReminderRow> findById(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM production_reminders WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<ProductionReminderRow> findByIdForUpdate(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM production_reminders WHERE id = ? FOR UPDATE",
                MAPPER, id).stream().findFirst();
    }

    /** 未完成待处理列表（可按订单明细与工序过滤）。 */
    public List<ProductionReminderRow> findIncomplete(Long orderItemId, String node) {
        var sql = new StringBuilder("SELECT " + COLUMNS
                + " FROM production_reminders WHERE reminder_type = ? AND status = ?");
        var args = new ArrayList<Object>();
        args.add(TYPE_INCOMPLETE);
        args.add(STATUS_OPEN);
        if (orderItemId != null) {
            sql.append(" AND order_item_id = ?");
            args.add(orderItemId);
        }
        if (node != null && !node.isBlank()) {
            sql.append(" AND node = ?");
            args.add(node.trim());
        }
        sql.append(" ORDER BY id");
        return jdbcTemplate.query(sql.toString(), MAPPER, args.toArray());
    }

    /** 处理提醒（暂不安排 / 已重新安排 / 部分安排）：只追加处理事实与原因，不改变数量事实。 */
    public int markHandled(long id, String handlingType, Integer handledQuantity, String reason,
                           String operatorUsername, String requestId) {
        return jdbcTemplate.update("""
                UPDATE production_reminders SET status = 'HANDLED', handling_type = ?, handled_quantity = ?,
                    reason = ?, handled_by = ?, handled_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6),
                    request_id = ?
                WHERE id = ? AND status = 'OPEN'
                """, handlingType, handledQuantity, reason, operatorUsername, requestId, id);
    }

    private static ProductionReminderRow map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp handledAt = rs.getTimestamp("handled_at");
        return new ProductionReminderRow(rs.getLong("id"), rs.getString("reminder_type"),
                rs.getLong("order_id"), rs.getLong("order_item_id"), rs.getString("node"),
                nullableLong(rs, "task_item_id"), nullableLong(rs, "verification_id"),
                nullableLong(rs, "preemption_id"), nullableLong(rs, "future_task_item_id"),
                rs.getInt("quantity"), rs.getString("status"), rs.getString("handling_type"),
                nullableInt(rs, "handled_quantity"), rs.getString("reason"), rs.getString("handled_by"),
                handledAt == null ? null : handledAt.toLocalDateTime());
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}

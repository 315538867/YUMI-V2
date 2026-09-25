package com.yumi.production.reminder.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * `production_reminders` 访问（阶段五）：提醒只辅助工作台，不是数量事实来源。
 * 未完成待处理由核验未完成生成；超额待核验在超额任务创建时生成、核验后结束；
 * 计划待调整在超额任务核验合格后生成，由人工「调整计划 / 无需调整」处理。
 */
@Repository
public class ProductionReminderRepository {

    public static final String TYPE_INCOMPLETE = "INCOMPLETE";
    public static final String TYPE_OVERTIME_PENDING_VERIFY = "OVERTIME_PENDING_VERIFY";
    public static final String TYPE_PLAN_ADJUSTMENT = "PLAN_ADJUSTMENT";

    public record ReminderRow(long id, String reminderType, long orderId, long orderItemId, String node,
                              Long planId, Long verificationId, Long preemptionId, Long futurePlanId,
                              int quantity, String status, String handlingType, Integer handledQuantity,
                              String reason) {
    }

    private static final String COLUMNS = """
            id, reminder_type, order_id, order_item_id, node, plan_id, verification_id, preemption_id,
            future_plan_id, quantity, status, handling_type, handled_quantity, reason
            """;

    private final JdbcTemplate jdbcTemplate;

    public ProductionReminderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 生成未完成待处理提醒（数量 = 计划数量 − 本次完成）。 */
    public long insertIncomplete(long orderId, long orderItemId, String node, long planId, Long verificationId,
                                 int quantity, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO production_reminders (reminder_type, order_id, order_item_id, node, plan_id,
                    verification_id, quantity, status, created_at, updated_at, request_id)
                VALUES ('INCOMPLETE', ?, ?, ?, ?, ?, ?, 'OPEN', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """, orderId, orderItemId, node, planId, verificationId, quantity, requestId);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM production_reminders WHERE reminder_type = 'INCOMPLETE' AND plan_id = ?
                """, Long.class, planId);
    }

    /** 超额任务创建时的「待核验」提醒，附着在受影响的未来计划行上。 */
    public long insertOvertimePendingVerify(long orderId, long orderItemId, String node, long overtimePlanId,
                                            long preemptionId, long futurePlanId, int quantity, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO production_reminders (reminder_type, order_id, order_item_id, node, plan_id,
                    preemption_id, future_plan_id, quantity, status, created_at, updated_at, request_id)
                VALUES ('OVERTIME_PENDING_VERIFY', ?, ?, ?, ?, ?, ?, ?, 'OPEN', UTC_TIMESTAMP(6),
                    UTC_TIMESTAMP(6), ?)
                """, orderId, orderItemId, node, overtimePlanId, preemptionId, futurePlanId, quantity, requestId);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM production_reminders
                WHERE reminder_type = 'OVERTIME_PENDING_VERIFY' AND preemption_id = ?
                """, Long.class, preemptionId);
    }

    /** 超额任务核验合格后的「计划待调整」提醒，数量 = 建议减少数量（按合格数量计算）。 */
    public long insertPlanAdjustment(long orderId, long orderItemId, String node, long overtimePlanId,
                                     Long verificationId, Long preemptionId, long futurePlanId, int quantity,
                                     String requestId) {
        jdbcTemplate.update("""
                INSERT INTO production_reminders (reminder_type, order_id, order_item_id, node, plan_id,
                    verification_id, preemption_id, future_plan_id, quantity, status, created_at, updated_at,
                    request_id)
                VALUES ('PLAN_ADJUSTMENT', ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', UTC_TIMESTAMP(6),
                    UTC_TIMESTAMP(6), ?)
                """, orderId, orderItemId, node, overtimePlanId, verificationId, preemptionId, futurePlanId,
                quantity, requestId);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM production_reminders
                WHERE reminder_type = 'PLAN_ADJUSTMENT' AND future_plan_id = ? AND plan_id = ?
                """, Long.class, futurePlanId, overtimePlanId);
    }

    public Optional<ReminderRow> findById(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM production_reminders WHERE id = ?", this::map, id)
                .stream().findFirst();
    }

    public Optional<ReminderRow> findByIdForUpdate(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM production_reminders WHERE id = ? FOR UPDATE",
                this::map, id).stream().findFirst();
    }

    public List<ReminderRow> findOpen(String type) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM production_reminders "
                + "WHERE reminder_type = ? AND status = 'OPEN' ORDER BY id", this::map, type);
    }

    /** 超额相关提醒：待核验 + 计划待调整（工作台超额区域）。 */
    public List<ReminderRow> findOpenOvertime() {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM production_reminders WHERE status = 'OPEN' "
                + "AND reminder_type IN ('OVERTIME_PENDING_VERIFY', 'PLAN_ADJUSTMENT') ORDER BY id", this::map);
    }

    public List<ReminderRow> findByOvertimePlan(long overtimePlanId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM production_reminders WHERE plan_id = ? ORDER BY id",
                this::map, overtimePlanId);
    }

    /** 部分安排：余量继续提醒（状态保持 OPEN，数量与已安排数量累加）。 */
    public void reduceQuantity(long id, int arranged, String requestId) {
        jdbcTemplate.update("""
                UPDATE production_reminders SET quantity = quantity - ?, handling_type = 'PARTIAL',
                    handled_quantity = COALESCE(handled_quantity, 0) + ?, updated_at = UTC_TIMESTAMP(6),
                    request_id = ?
                WHERE id = ?
                """, arranged, arranged, requestId, id);
    }

    /** 关闭提醒（重新安排/部分安排/暂不安排/无需调整/已调整）。 */
    public void markHandled(long id, String handlingType, Integer handledQuantity, String reason,
                            String handledBy, String requestId) {
        jdbcTemplate.update("""
                UPDATE production_reminders SET status = 'HANDLED', handling_type = ?, handled_quantity = ?,
                    reason = ?, handled_by = ?, handled_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6),
                    request_id = ?
                WHERE id = ?
                """, handlingType, handledQuantity, reason, handledBy, requestId, id);
    }

    private ReminderRow map(ResultSet rs, int rowNum) throws SQLException {
        return new ReminderRow(rs.getLong("id"), rs.getString("reminder_type"), rs.getLong("order_id"),
                rs.getLong("order_item_id"), rs.getString("node"), nullableLong(rs, "plan_id"),
                nullableLong(rs, "verification_id"), nullableLong(rs, "preemption_id"),
                nullableLong(rs, "future_plan_id"), rs.getInt("quantity"), rs.getString("status"),
                rs.getString("handling_type"), nullableInt(rs, "handled_quantity"), rs.getString("reason"));
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

package com.yumi.production.overtime.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * `overtime_tasks` / `overtime_task_items` / `overtime_preemptions` 与超额提醒行访问（阶段五 5.14）。
 *
 * 所有竞争性汇总与预占校验使用 `FOR UPDATE` 锁定读（施工文档 §11.2/§11.3）：
 * 超额头/明细按 id 升序 → 未来 `production_task_items` 按 id 升序 → 预占。
 * 未来明细的 `planned_quantity` 只读不写；预占只在 `overtime_preemptions` 落事实。
 */
@Repository
public class OvertimeTaskRepository {

    public static final String TYPE_OVERTIME_PENDING_VERIFY = "OVERTIME_PENDING_VERIFY";
    public static final String TYPE_PLAN_ADJUSTMENT = "PLAN_ADJUSTMENT";

    public record OvertimeTaskRow(long id, String taskNo, LocalDate taskDate, long employeeId,
                                  String employeeNameSnapshot, long workTypeId, String workTypeNameSnapshot,
                                  String note, long version) {
    }

    public record OvertimeItemRow(long id, long taskId, int itemNo, long orderId, long orderItemId, long productId,
                                  String productNo, String productName, String node, int plannedQuantity,
                                  String status, Integer completedQuantity, Integer qualifiedQuantity,
                                  Integer reworkQuantity, Integer scrapQuantity, Integer incompleteQuantity,
                                  String verifyNote, String verifiedBy, LocalDateTime verifiedAt, long version) {
    }

    public record OvertimePreemptionRow(long id, long overtimeTaskItemId, long futureTaskItemId, long orderId,
                                        long orderItemId, String node, int preemptedQuantity, String status,
                                        LocalDateTime releasedAt, String releasedBy, String releaseReason) {
    }

    /** 未来 `production_task_items` 行（超额来源）：只读引用，永不修改。 */
    public record FutureItemRow(long id, long taskId, long orderId, long orderItemId, long productId, String productNo,
                                String productName, String node, int plannedQuantity, String sourceType,
                                String status) {
    }

    /** 未来任务头信息：任务日期与类型在创建后不可变，读取不需要加锁。 */
    public record FutureTaskInfo(long taskId, LocalDate taskDate, String taskType) {
    }

    public record WorkTypeRow(long id, String code, String name) {
    }

    public record ReminderRow(long id, String reminderType, long orderId, long orderItemId, String node,
                              Long preemptionId, Long futureTaskItemId, int quantity, String status,
                              String handlingType, String reason) {
    }

    private static final String TASK_COLUMNS = """
            id, task_no, task_date, employee_id, employee_name_snapshot, work_type_id,
            work_type_name_snapshot, note, version
            """;

    private static final String ITEM_COLUMNS = """
            id, task_id, item_no, order_id, order_item_id, product_id, product_no, product_name, node,
            planned_quantity, status, completed_quantity, qualified_quantity, rework_quantity, scrap_quantity,
            incomplete_quantity, verify_note, verified_by, verified_at, version
            """;

    private static final String PREEMPTION_COLUMNS = """
            id, overtime_task_item_id, future_task_item_id, order_id, order_item_id, node, preempted_quantity,
            status, released_at, released_by, release_reason
            """;

    /** 与 `PREEMPTION_COLUMNS` 同列，供带表别名的连接查询使用。 */
    private static final String TASK_PREEMPTION_COLUMNS = """
            p.id, p.overtime_task_item_id, p.future_task_item_id, p.order_id, p.order_item_id, p.node,
            p.preempted_quantity, p.status, p.released_at, p.released_by, p.release_reason
            """;

    private final JdbcTemplate jdbcTemplate;

    public OvertimeTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------- 超额任务头 ----------

    public long insertTask(String taskNo, LocalDate taskDate, long employeeId, String employeeName,
                           long workTypeId, String workTypeName, String note, String requestId,
                           String idempotencyKey) {
        jdbcTemplate.update("""
                INSERT INTO overtime_tasks (task_no, task_date, employee_id, employee_name_snapshot, work_type_id,
                    work_type_name_snapshot, note, version, created_at, updated_at, request_id, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, ?)
                """, taskNo, Date.valueOf(taskDate), employeeId, employeeName, workTypeId, workTypeName, note,
                requestId, idempotencyKey);
        return jdbcTemplate.queryForObject("SELECT id FROM overtime_tasks WHERE task_no = ?", Long.class, taskNo);
    }

    /** 按执行日期列出超额任务（工作台提醒读取；只读，不加锁）。 */
    public List<OvertimeTaskRow> findTasks(LocalDate taskDate) {
        if (taskDate == null) {
            return jdbcTemplate.query("SELECT " + TASK_COLUMNS
                    + " FROM overtime_tasks ORDER BY task_date DESC, id DESC", OvertimeTaskRepository::mapTask);
        }
        return jdbcTemplate.query("SELECT " + TASK_COLUMNS
                + " FROM overtime_tasks WHERE task_date = ? ORDER BY id",
                OvertimeTaskRepository::mapTask, taskDate);
    }

    public Optional<OvertimeTaskRow> findTaskById(long id) {
        return jdbcTemplate.query("SELECT " + TASK_COLUMNS + " FROM overtime_tasks WHERE id = ?",
                OvertimeTaskRepository::mapTask, id).stream().findFirst();
    }

    /** 核验前先锁超额头（锁定顺序第一步）。 */
    public Optional<OvertimeTaskRow> findTaskByIdForUpdate(long id) {
        return jdbcTemplate.query("SELECT " + TASK_COLUMNS + " FROM overtime_tasks WHERE id = ? FOR UPDATE",
                OvertimeTaskRepository::mapTask, id).stream().findFirst();
    }

    // ---------- 超额明细 ----------

    public long insertItem(long taskId, int itemNo, long orderId, long orderItemId, long productId, String productNo,
                           String productName, String node, int plannedQuantity, String requestId,
                           String idempotencyKey) {
        jdbcTemplate.update("""
                INSERT INTO overtime_task_items (task_id, item_no, order_id, order_item_id, product_id, product_no,
                    product_name, node, planned_quantity, status, version, created_at, updated_at, request_id,
                    idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, ?)
                """, taskId, itemNo, orderId, orderItemId, productId, productNo, productName, node, plannedQuantity,
                requestId, idempotencyKey);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM overtime_task_items WHERE task_id = ? AND item_no = ?", Long.class, taskId, itemNo);
    }

    public List<OvertimeItemRow> findItemsByTask(long taskId) {
        return jdbcTemplate.query("SELECT " + ITEM_COLUMNS + " FROM overtime_task_items WHERE task_id = ? "
                + "ORDER BY item_no", OvertimeTaskRepository::mapItem, taskId);
    }

    /** 按明细 id 升序锁定任务内全部明细（锁定顺序第二步）。 */
    public List<OvertimeItemRow> findItemsByTaskForUpdate(long taskId) {
        return jdbcTemplate.query("SELECT " + ITEM_COLUMNS + " FROM overtime_task_items WHERE task_id = ? "
                + "ORDER BY id FOR UPDATE", OvertimeTaskRepository::mapItem, taskId);
    }

    /** 明细核验：PENDING → VERIFIED；带状态与核验列条件，重复核验返回 0 行。 */
    public int markItemVerified(long itemId, int completed, int qualified, int rework, int scrap, int incomplete,
                                String note, String verifiedBy, String requestId, String idempotencyKey) {
        return jdbcTemplate.update("""
                UPDATE overtime_task_items SET status = 'VERIFIED', completed_quantity = ?, qualified_quantity = ?,
                    rework_quantity = ?, scrap_quantity = ?, incomplete_quantity = ?, verify_note = ?,
                    verified_by = ?, verified_at = UTC_TIMESTAMP(6), version = version + 1,
                    updated_at = UTC_TIMESTAMP(6), request_id = ?, idempotency_key = ?
                WHERE id = ? AND status = 'PENDING' AND verified_at IS NULL
                """, completed, qualified, rework, scrap, incomplete, note, verifiedBy, requestId, idempotencyKey,
                itemId);
    }

    // ---------- 未来 NORMAL 明细（只读引用 + 锁定） ----------

    /** 按未来明细 id 升序锁定来源明细行；不锁任务头（任务日期与类型创建后不可变）。 */
    public List<FutureItemRow> lockFutureItems(Collection<Long> futureTaskItemIds) {
        if (futureTaskItemIds.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                SELECT id, task_id, order_id, order_item_id, product_id, product_no, product_name, node,
                       planned_quantity, source_type, status
                FROM production_task_items
                WHERE id IN (%s)
                ORDER BY id
                FOR UPDATE
                """.formatted(placeholders(futureTaskItemIds.size())),
                OvertimeTaskRepository::mapFutureItem, futureTaskItemIds.toArray());
    }

    /** 未来任务头的日期与类型（创建后不可变，普通读即可）。 */
    public Map<Long, FutureTaskInfo> findFutureTaskInfo(Collection<Long> taskIds) {
        var result = new LinkedHashMap<Long, FutureTaskInfo>();
        if (taskIds.isEmpty()) {
            return result;
        }
        jdbcTemplate.query("""
                SELECT id, task_date, task_type FROM production_tasks
                WHERE id IN (%s) ORDER BY id
                """.formatted(placeholders(taskIds.size())), rs -> {
            result.put(rs.getLong("id"), new FutureTaskInfo(rs.getLong("id"),
                    rs.getDate("task_date").toLocalDate(), rs.getString("task_type")));
        }, taskIds.toArray());
        return result;
    }

    // ---------- 预占 ----------

    public long insertPreemption(long overtimeTaskItemId, long futureTaskItemId, long orderId, long orderItemId,
                                 String node, int preemptedQuantity, String requestId, String idempotencyKey) {
        jdbcTemplate.update("""
                INSERT INTO overtime_preemptions (overtime_task_item_id, future_task_item_id, order_id,
                    order_item_id, node, preempted_quantity, status, created_at, updated_at, request_id,
                    idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, ?)
                """, overtimeTaskItemId, futureTaskItemId, orderId, orderItemId, node, preemptedQuantity, requestId,
                idempotencyKey);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM overtime_preemptions WHERE overtime_task_item_id = ? AND future_task_item_id = ?
                """, Long.class, overtimeTaskItemId, futureTaskItemId);
    }

    /** 按预占 id 升序锁定这些未来明细上的有效预占（锁定顺序第三步）。 */
    public List<OvertimePreemptionRow> lockActivePreemptions(Collection<Long> futureTaskItemIds) {
        if (futureTaskItemIds.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query("SELECT " + PREEMPTION_COLUMNS + " FROM overtime_preemptions "
                + "WHERE future_task_item_id IN (" + placeholders(futureTaskItemIds.size())
                + ") AND status = 'ACTIVE' ORDER BY id FOR UPDATE",
                OvertimeTaskRepository::mapPreemption, futureTaskItemIds.toArray());
    }

    public List<OvertimePreemptionRow> findPreemptionsByTask(long taskId) {
        return jdbcTemplate.query("SELECT " + TASK_PREEMPTION_COLUMNS + " FROM overtime_preemptions p "
                + "JOIN overtime_task_items i ON i.id = p.overtime_task_item_id WHERE i.task_id = ? ORDER BY p.id",
                OvertimeTaskRepository::mapPreemption, taskId);
    }

    /** 核验时按预占 id 升序锁定本任务全部预占（锁定顺序第三步）。 */
    public List<OvertimePreemptionRow> findPreemptionsByTaskForUpdate(long taskId) {
        return jdbcTemplate.query("SELECT " + TASK_PREEMPTION_COLUMNS + " FROM overtime_preemptions p "
                + "JOIN overtime_task_items i ON i.id = p.overtime_task_item_id WHERE i.task_id = ? "
                + "ORDER BY p.id FOR UPDATE", OvertimeTaskRepository::mapPreemption, taskId);
    }

    /** 释放该超额任务全部仍有效的预占（核验事务内调用）。 */
    public int releaseActivePreemptions(long taskId, String reason, String releasedBy, String requestId) {
        return jdbcTemplate.update("""
                UPDATE overtime_preemptions SET status = 'RELEASED', released_at = UTC_TIMESTAMP(6),
                    released_by = ?, release_reason = ?, updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE status = 'ACTIVE'
                  AND overtime_task_item_id IN (SELECT id FROM overtime_task_items WHERE task_id = ?)
                """, releasedBy, reason, requestId, taskId);
    }

    // ---------- 提醒（只辅助工作台，不是数量事实） ----------

    /** 创建时的「超额待核验」提醒：每条超额明细一条，挂在对应预占上。 */
    public long insertPendingVerifyReminder(long orderId, long orderItemId, String node, long preemptionId,
                                            long futureTaskItemId, int quantity, String requestId,
                                            String idempotencyKey) {
        jdbcTemplate.update("""
                INSERT INTO production_reminders (reminder_type, order_id, order_item_id, node, preemption_id,
                    future_task_item_id, quantity, status, created_at, updated_at, request_id, idempotency_key)
                VALUES ('OVERTIME_PENDING_VERIFY', ?, ?, ?, ?, ?, ?, 'OPEN', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
                    ?, ?)
                """, orderId, orderItemId, node, preemptionId, futureTaskItemId, quantity, requestId, idempotencyKey);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM production_reminders WHERE preemption_id = ? AND reminder_type = "
                        + "'OVERTIME_PENDING_VERIFY'", Long.class, preemptionId);
    }

    /** 核验合格后的「计划待调整」提醒：建议数量 = 该预占分摊到的合格数量。 */
    public long insertPlanAdjustmentReminder(long orderId, long orderItemId, String node, long preemptionId,
                                             long futureTaskItemId, int quantity, String requestId,
                                             String idempotencyKey) {
        jdbcTemplate.update("""
                INSERT INTO production_reminders (reminder_type, order_id, order_item_id, node, preemption_id,
                    future_task_item_id, quantity, status, created_at, updated_at, request_id, idempotency_key)
                VALUES ('PLAN_ADJUSTMENT', ?, ?, ?, ?, ?, ?, 'OPEN', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, ?)
                """, orderId, orderItemId, node, preemptionId, futureTaskItemId, quantity, requestId, idempotencyKey);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM production_reminders WHERE preemption_id = ? AND reminder_type = 'PLAN_ADJUSTMENT'",
                Long.class, preemptionId);
    }

    /** 结束该任务仍 OPEN 的「超额待核验」提醒（核验事务内调用）。 */
    public int closeOpenPendingVerifyReminders(Collection<Long> preemptionIds, String handlingType, String reason,
                                               String handledBy, String requestId, String idempotencyKey) {
        if (preemptionIds.isEmpty()) {
            return 0;
        }
        // 注意：IN (...) 的占位符参数必须展开成独立参数，不能把集合作为单个参数传入
        var args = new java.util.ArrayList<Object>();
        args.add(handlingType);
        args.add(reason);
        args.add(handledBy);
        args.add(requestId);
        args.add(idempotencyKey);
        args.addAll(preemptionIds);
        return jdbcTemplate.update("""
                UPDATE production_reminders SET status = 'HANDLED', handling_type = ?, reason = ?, handled_by = ?,
                    handled_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6), request_id = ?,
                    idempotency_key = ?
                WHERE reminder_type = 'OVERTIME_PENDING_VERIFY' AND status = 'OPEN'
                  AND preemption_id IN (%s)
                """.formatted(placeholders(preemptionIds.size())), args.toArray());
    }

    public List<ReminderRow> findRemindersByPreemptions(Collection<Long> preemptionIds) {
        if (preemptionIds.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                SELECT id, reminder_type, order_id, order_item_id, node, preemption_id, future_task_item_id,
                       quantity, status, handling_type, reason
                FROM production_reminders WHERE preemption_id IN (%s) ORDER BY id
                """.formatted(placeholders(preemptionIds.size())), OvertimeTaskRepository::mapReminder,
                preemptionIds.toArray());
    }

    // ---------- 静态目录只读引用 ----------

    /** 工序 code 即系统内置工种 code（`work_types.code`）；超额任务头保存工种 id 与名称快照。 */
    public Optional<WorkTypeRow> findWorkTypeByCode(String code) {
        return jdbcTemplate.query("SELECT id, code, name FROM work_types WHERE code = ?",
                rs -> rs.next() ? Optional.of(new WorkTypeRow(rs.getLong("id"), rs.getString("code"),
                        rs.getString("name"))) : Optional.empty(), code);
    }

    public Optional<WorkTypeRow> findWorkTypeById(long id) {
        return jdbcTemplate.query("SELECT id, code, name FROM work_types WHERE id = ?",
                rs -> rs.next() ? Optional.of(new WorkTypeRow(rs.getLong("id"), rs.getString("code"),
                        rs.getString("name"))) : Optional.empty(), id);
    }

    // ---------- 映射 ----------

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static OvertimeTaskRow mapTask(ResultSet rs, int rowNum) throws SQLException {
        return new OvertimeTaskRow(rs.getLong("id"), rs.getString("task_no"),
                rs.getDate("task_date").toLocalDate(), rs.getLong("employee_id"),
                rs.getString("employee_name_snapshot"), rs.getLong("work_type_id"),
                rs.getString("work_type_name_snapshot"), rs.getString("note"), rs.getLong("version"));
    }

    private static OvertimeItemRow mapItem(ResultSet rs, int rowNum) throws SQLException {
        return new OvertimeItemRow(rs.getLong("id"), rs.getLong("task_id"), rs.getInt("item_no"),
                rs.getLong("order_id"), rs.getLong("order_item_id"), rs.getLong("product_id"),
                rs.getString("product_no"), rs.getString("product_name"), rs.getString("node"),
                rs.getInt("planned_quantity"), rs.getString("status"), nullableInt(rs, "completed_quantity"),
                nullableInt(rs, "qualified_quantity"), nullableInt(rs, "rework_quantity"),
                nullableInt(rs, "scrap_quantity"), nullableInt(rs, "incomplete_quantity"),
                rs.getString("verify_note"), rs.getString("verified_by"), nullableTime(rs, "verified_at"),
                rs.getLong("version"));
    }

    private static OvertimePreemptionRow mapPreemption(ResultSet rs, int rowNum) throws SQLException {
        return new OvertimePreemptionRow(rs.getLong("id"), rs.getLong("overtime_task_item_id"),
                rs.getLong("future_task_item_id"), rs.getLong("order_id"), rs.getLong("order_item_id"),
                rs.getString("node"), rs.getInt("preempted_quantity"), rs.getString("status"),
                nullableTime(rs, "released_at"), rs.getString("released_by"), rs.getString("release_reason"));
    }

    private static FutureItemRow mapFutureItem(ResultSet rs, int rowNum) throws SQLException {
        return new FutureItemRow(rs.getLong("id"), rs.getLong("task_id"), rs.getLong("order_id"),
                rs.getLong("order_item_id"), rs.getLong("product_id"), rs.getString("product_no"),
                rs.getString("product_name"), rs.getString("node"), rs.getInt("planned_quantity"),
                rs.getString("source_type"), rs.getString("status"));
    }

    private static ReminderRow mapReminder(ResultSet rs, int rowNum) throws SQLException {
        return new ReminderRow(rs.getLong("id"), rs.getString("reminder_type"), rs.getLong("order_id"),
                rs.getLong("order_item_id"), rs.getString("node"), nullableLong(rs, "preemption_id"),
                nullableLong(rs, "future_task_item_id"), rs.getInt("quantity"), rs.getString("status"),
                rs.getString("handling_type"), rs.getString("reason"));
    }

    /** MySQL INT UNSIGNED 经驱动可能返回 Long，统一按 int 取值并保留 null。 */
    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDateTime nullableTime(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }
}

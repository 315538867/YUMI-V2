package com.yumi.production.task.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 生产任务头与任务明细访问（阶段五 5.2/5.5）：显式列读写，不引入 JPA 实体。
 * 任务头不含数量汇总列；容量、待安排与可执行量一律由明细与事实表汇总。
 * 所有竞争性汇总查询使用 `FOR UPDATE` 锁定读（锁定顺序见 `production-module-design.md` §11.2）。
 */
@Repository
public class ProductionTaskRepository {

    private static final String TASK_COLUMNS = """
            id, task_no, task_date, employee_id, employee_name_snapshot, work_type_id,
            work_type_name_snapshot, task_type, note, version
            """;

    private static final String ITEM_COLUMNS = """
            id, task_id, item_no, order_id, order_item_id, product_id, product_no, product_name, node,
            planned_quantity, source_type, source_id, standard_minutes, estimated_minutes,
            making_effective_hour_rate, workday_hours, mold_quantity, daily_batch_limit,
            status, cancelled_by, cancel_reason, version
            """;

    private static final RowMapper<ProductionTaskRow> TASK_MAPPER = ProductionTaskRepository::mapTask;
    private static final RowMapper<ProductionTaskItemRow> ITEM_MAPPER = ProductionTaskRepository::mapItem;

    private final JdbcTemplate jdbcTemplate;

    public ProductionTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------- 任务头 ----------

    public long insertTask(ProductionTaskRow row, String requestId, String idempotencyKey) {
        jdbcTemplate.update("""
                INSERT INTO production_tasks (task_no, task_date, employee_id, employee_name_snapshot,
                    work_type_id, work_type_name_snapshot, task_type, note,
                    version, created_at, updated_at, request_id, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, ?)
                """,
                row.taskNo(), Date.valueOf(row.taskDate()), row.employeeId(), row.employeeNameSnapshot(),
                row.workTypeId(), row.workTypeNameSnapshot(), row.taskType(), row.note(),
                requestId, idempotencyKey);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM production_tasks WHERE task_no = ?", Long.class, row.taskNo());
    }

    public Optional<ProductionTaskRow> findTaskById(long id) {
        return jdbcTemplate.query("SELECT " + TASK_COLUMNS + " FROM production_tasks WHERE id = ?",
                TASK_MAPPER, id).stream().findFirst();
    }

    /** 批量核验/取消前先锁任务头（锁定顺序第一步）。 */
    public Optional<ProductionTaskRow> findTaskByIdForUpdate(long id) {
        return jdbcTemplate.query("SELECT " + TASK_COLUMNS + " FROM production_tasks WHERE id = ? FOR UPDATE",
                TASK_MAPPER, id).stream().findFirst();
    }

    /** 工作台筛选：日期、员工、工种、类型、状态（状态在服务层按明细派生后再过滤）。 */
    public List<ProductionTaskRow> findTasks(LocalDate dateFrom, LocalDate dateTo, Long employeeId,
                                             Long workTypeId, String taskType, Long orderId, Long productId) {
        var sql = new StringBuilder("""
                SELECT DISTINCT t.id, t.task_no, t.task_date, t.employee_id, t.employee_name_snapshot,
                       t.work_type_id, t.work_type_name_snapshot, t.task_type, t.note, t.version
                FROM production_tasks t""");
        var args = new ArrayList<Object>();
        if (orderId != null || productId != null) {
            sql.append(" JOIN production_task_items i ON i.task_id = t.id");
            if (orderId != null) {
                sql.append(" AND i.order_id = ?");
                args.add(orderId);
            }
            if (productId != null) {
                sql.append(" AND i.product_id = ?");
                args.add(productId);
            }
        }
        sql.append(" WHERE 1=1");
        if (dateFrom != null) {
            sql.append(" AND t.task_date >= ?");
            args.add(Date.valueOf(dateFrom));
        }
        if (dateTo != null) {
            sql.append(" AND t.task_date <= ?");
            args.add(Date.valueOf(dateTo));
        }
        if (employeeId != null) {
            sql.append(" AND t.employee_id = ?");
            args.add(employeeId);
        }
        if (workTypeId != null) {
            sql.append(" AND t.work_type_id = ?");
            args.add(workTypeId);
        }
        if (taskType != null && !taskType.isBlank()) {
            sql.append(" AND t.task_type = ?");
            args.add(taskType.trim());
        }
        sql.append(" ORDER BY t.task_date DESC, t.id DESC");
        return jdbcTemplate.query(sql.toString(), TASK_MAPPER, args.toArray());
    }

    public void bumpTaskVersion(long taskId, String requestId) {
        jdbcTemplate.update("""
                UPDATE production_tasks SET version = version + 1, updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ?
                """, requestId, taskId);
    }

    // ---------- 任务明细 ----------

    public long insertItem(ProductionTaskItemRow row, String requestId, String idempotencyKey) {
        jdbcTemplate.update("""
                INSERT INTO production_task_items (task_id, item_no, order_id, order_item_id, product_id,
                    product_no, product_name, node, planned_quantity, source_type, source_id,
                    standard_minutes, estimated_minutes, making_effective_hour_rate, workday_hours,
                    mold_quantity, daily_batch_limit, status,
                    version, created_at, updated_at, request_id, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING',
                    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, ?)
                """,
                row.taskId(), row.itemNo(), row.orderId(), row.orderItemId(), row.productId(),
                row.productNo(), row.productName(), row.node(), row.plannedQuantity(), row.sourceType(),
                row.sourceId(), row.standardMinutes(), row.estimatedMinutes(),
                row.makingEffectiveHourRate(), row.workdayHours(), row.moldQuantity(), row.dailyBatchLimit(),
                requestId, idempotencyKey);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM production_task_items WHERE task_id = ? AND item_no = ?",
                Long.class, row.taskId(), row.itemNo());
    }

    public Optional<ProductionTaskItemRow> findItemByIdForUpdate(long itemId) {
        return jdbcTemplate.query("SELECT " + ITEM_COLUMNS
                + " FROM production_task_items WHERE id = ? FOR UPDATE", ITEM_MAPPER, itemId)
                .stream().findFirst();
    }

    /** 按明细 id 升序锁定任务内全部明细（锁定顺序第二步）。 */
    public List<ProductionTaskItemRow> findItemsByTaskForUpdate(long taskId) {
        return jdbcTemplate.query("SELECT " + ITEM_COLUMNS
                + " FROM production_task_items WHERE task_id = ? ORDER BY id FOR UPDATE", ITEM_MAPPER, taskId);
    }

    public List<ProductionTaskItemRow> findItemsByTask(long taskId) {
        return jdbcTemplate.query("SELECT " + ITEM_COLUMNS
                + " FROM production_task_items WHERE task_id = ? ORDER BY item_no", ITEM_MAPPER, taskId);
    }

    public List<ProductionTaskItemRow> findItemsByTasks(Collection<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query("SELECT " + ITEM_COLUMNS + " FROM production_task_items WHERE task_id IN ("
                        + placeholders(taskIds.size()) + ") ORDER BY task_id, item_no",
                ITEM_MAPPER, taskIds.toArray());
    }

    /** 明细核验：PENDING → VERIFIED（带状态条件，重复核验由唯一键与状态条件共同兜底）。 */
    public int markItemVerified(long itemId, String requestId) {
        return jdbcTemplate.update("""
                UPDATE production_task_items SET status = 'VERIFIED', version = version + 1,
                    updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ? AND status = 'PENDING'
                """, requestId, itemId);
    }

    /** 明细取消：只允许 PENDING 明细，原因必填。 */
    public int markItemCancelled(long itemId, String reason, String operatorUsername, String requestId) {
        return jdbcTemplate.update("""
                UPDATE production_task_items SET status = 'CANCELLED', cancelled_at = UTC_TIMESTAMP(6),
                    cancelled_by = ?, cancel_reason = ?, version = version + 1,
                    updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ? AND status = 'PENDING'
                """, operatorUsername, reason, requestId, itemId);
    }

    // ---------- 工种 ----------

    /** 工种 id → 系统 code（生产任务的工序即工种 code）。 */
    public String workTypeCode(long workTypeId) {
        var codes = jdbcTemplate.queryForList(
                "SELECT code FROM work_types WHERE id = ?", String.class, workTypeId);
        return codes.isEmpty() ? null : codes.get(0);
    }

    /** 工种 code → 显示名称快照。 */
    public String workTypeName(String code) {
        var names = jdbcTemplate.queryForList(
                "SELECT name FROM work_types WHERE code = ?", String.class, code);
        return names.isEmpty() ? code : names.get(0);
    }

    /** 工种 code → 工种 id（返工来源创建任务时按来源发生工序解析）。 */
    public Long workTypeId(String code) {
        var ids = jdbcTemplate.queryForList("SELECT id FROM work_types WHERE code = ?", Long.class, code);
        return ids.isEmpty() ? null : ids.get(0);
    }

    // ---------- 派生汇总 ----------

    /**
     * 锁定并汇总「产品 + 任务日期 + 工序」上未取消的 NORMAL 正常来源计划数量（产品日产能硬约束）。
     * 历史已核验明细仍占用该日期资源，只有取消才释放。
     */
    public int lockNormalCapacityUsage(long productId, LocalDate taskDate, String node) {
        var quantities = jdbcTemplate.queryForList("""
                SELECT i.planned_quantity FROM production_task_items i
                JOIN production_tasks t ON t.id = i.task_id
                WHERE i.product_id = ? AND t.task_date = ? AND i.node = ?
                  AND i.status <> 'CANCELLED' AND i.source_type IN ('ORDER', 'QUANTITY_RETURN')
                ORDER BY i.id
                FOR UPDATE
                """, Integer.class, productId, Date.valueOf(taskDate), node);
        return quantities.stream().mapToInt(Integer::intValue).sum();
    }

    /** 只读的产能占用（列表/详情展示用；写命令必须走锁定版本）。 */
    public int normalCapacityUsage(long productId, LocalDate taskDate, String node) {
        var total = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(i.planned_quantity), 0) FROM production_task_items i
                JOIN production_tasks t ON t.id = i.task_id
                WHERE i.product_id = ? AND t.task_date = ? AND i.node = ?
                  AND i.status <> 'CANCELLED' AND i.source_type IN ('ORDER', 'QUANTITY_RETURN')
                """, Integer.class, productId, Date.valueOf(taskDate), node);
        return total == null ? 0 : total;
    }

    /** 指定订单明细/工序上未取消的 NORMAL 正常来源待执行计划数量（待安排上限）。 */
    public Map<String, Integer> pendingNormalByItemNode(Collection<Long> orderItemIds) {
        return sumByItemNode("""
                SELECT order_item_id, node, SUM(planned_quantity) total FROM production_task_items
                WHERE order_item_id IN (%s) AND status = 'PENDING'
                  AND source_type IN ('ORDER', 'QUANTITY_RETURN')
                GROUP BY order_item_id, node
                """, orderItemIds);
    }

    /**
     * 指定订单明细/工序上已核验的**正常处理**数量（来自不可变核验事实，不读投影）。
     * 只统计正常来源明细：返工核验、超额核验与其他排班不得混入正常处理量。
     */
    public Map<String, Integer> verifiedByItemNode(Collection<Long> orderItemIds) {
        return sumByItemNode("""
                SELECT v.order_item_id, v.node, SUM(v.completed_quantity) total
                FROM production_verifications v
                JOIN production_task_items i ON i.id = v.task_item_id
                WHERE v.order_item_id IN (%s) AND i.source_type IN ('ORDER', 'QUANTITY_RETURN')
                GROUP BY v.order_item_id, v.node
                """, orderItemIds);
    }

    /** 指定订单明细/工序上已核验的**正常合格**数量。 */
    public Map<String, Integer> qualifiedByItemNode(Collection<Long> orderItemIds) {
        return sumByItemNode("""
                SELECT v.order_item_id, v.node, SUM(v.qualified_quantity) total
                FROM production_verifications v
                JOIN production_task_items i ON i.id = v.task_item_id
                WHERE v.order_item_id IN (%s) AND i.source_type IN ('ORDER', 'QUANTITY_RETURN')
                GROUP BY v.order_item_id, v.node
                """, orderItemIds);
    }

    private Map<String, Integer> sumByItemNode(String template, Collection<Long> orderItemIds) {
        var result = new LinkedHashMap<String, Integer>();
        if (orderItemIds.isEmpty()) {
            return result;
        }
        jdbcTemplate.query(template.formatted(placeholders(orderItemIds.size())), rs -> {
            result.put(rs.getLong("order_item_id") + ":" + rs.getString("node"), rs.getInt("total"));
        }, orderItemIds.toArray());
        return result;
    }

    /** 工作台/详情用：同一订单明细与工序上未取消的正常来源待执行明细（按日期、id 升序先到先得）。 */
    public List<ProductionTaskItemRow> findExecutableCandidates(long orderItemId, String node) {
        return jdbcTemplate.query("""
                SELECT i.id, i.task_id, i.item_no, i.order_id, i.order_item_id, i.product_id, i.product_no,
                       i.product_name, i.node, i.planned_quantity, i.source_type, i.source_id,
                       i.standard_minutes, i.estimated_minutes, i.making_effective_hour_rate,
                       i.workday_hours, i.mold_quantity, i.daily_batch_limit, i.status, i.cancelled_by,
                       i.cancel_reason, i.version
                FROM production_task_items i
                JOIN production_tasks t ON t.id = i.task_id
                WHERE i.order_item_id = ? AND i.node = ? AND i.status = 'PENDING'
                  AND i.source_type IN ('ORDER', 'QUANTITY_RETURN')
                ORDER BY t.task_date, i.id
                """, ITEM_MAPPER, orderItemId, node);
    }

    /** 未来日期、仍待执行、类型为 NORMAL 且来源为订单需求的明细（超额预占的来源范围）。 */
    public List<ProductionTaskItemRow> findFutureNormalItems(LocalDate afterDate) {
        return jdbcTemplate.query("""
                SELECT i.id, i.task_id, i.item_no, i.order_id, i.order_item_id, i.product_id, i.product_no,
                       i.product_name, i.node, i.planned_quantity, i.source_type, i.source_id,
                       i.standard_minutes, i.estimated_minutes, i.making_effective_hour_rate,
                       i.workday_hours, i.mold_quantity, i.daily_batch_limit, i.status, i.cancelled_by,
                       i.cancel_reason, i.version
                FROM production_task_items i
                JOIN production_tasks t ON t.id = i.task_id
                WHERE t.task_date > ? AND t.task_type = 'NORMAL' AND i.status = 'PENDING'
                  AND i.source_type = 'ORDER'
                ORDER BY t.task_date, i.id
                """, ITEM_MAPPER, Date.valueOf(afterDate));
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static ProductionTaskRow mapTask(ResultSet rs, int rowNum) throws SQLException {
        return new ProductionTaskRow(rs.getLong("id"), rs.getString("task_no"),
                rs.getDate("task_date").toLocalDate(), rs.getLong("employee_id"),
                rs.getString("employee_name_snapshot"), rs.getLong("work_type_id"),
                rs.getString("work_type_name_snapshot"), rs.getString("task_type"), rs.getString("note"),
                rs.getLong("version"));
    }

    private static ProductionTaskItemRow mapItem(ResultSet rs, int rowNum) throws SQLException {
        return new ProductionTaskItemRow(rs.getLong("id"), rs.getLong("task_id"), rs.getInt("item_no"),
                rs.getLong("order_id"), rs.getLong("order_item_id"), rs.getLong("product_id"),
                rs.getString("product_no"), rs.getString("product_name"), rs.getString("node"),
                rs.getInt("planned_quantity"), rs.getString("source_type"), rs.getLong("source_id"),
                rs.getInt("standard_minutes"), rs.getLong("estimated_minutes"),
                rs.getBigDecimal("making_effective_hour_rate"), rs.getBigDecimal("workday_hours"),
                rs.getInt("mold_quantity"), rs.getInt("daily_batch_limit"), rs.getString("status"),
                rs.getString("cancelled_by"), rs.getString("cancel_reason"), rs.getLong("version"));
    }
}

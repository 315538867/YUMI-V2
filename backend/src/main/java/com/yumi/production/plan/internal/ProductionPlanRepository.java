package com.yumi.production.plan.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * `production_plans` 与 `production_verifications` 访问（阶段五）：显式列读写。
 * 待安排与当前可执行需要「按工序的计划占用」和「按工序的已核验处理」，两者分别来自计划表与核验表，
 * 都按 (order_item_id, node) 汇总——不依赖订单侧投影，保证可从事实重建。
 */
@Repository
public class ProductionPlanRepository {

    private static final String COLUMNS = """
            id, plan_no, plan_type, order_id, order_item_id, node, plan_date, employee_id, employee_name,
            quantity, status, source_type, source_id, source_line_id, note, version
            """;

    private static final RowMapper<ProductionPlanRow> MAPPER = ProductionPlanRepository::map;

    private final JdbcTemplate jdbcTemplate;

    public ProductionPlanRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(String planNo, String planType, long orderId, long orderItemId, String node,
                       LocalDate planDate, long employeeId, String employeeName, int quantity,
                       String sourceType, long sourceId, long sourceLineId, String note, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO production_plans (plan_no, plan_type, order_id, order_item_id, node, plan_date,
                    employee_id, employee_name, quantity, status, source_type, source_id, source_line_id, note,
                    version, created_at, updated_at, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, 0,
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """, planNo, planType, orderId, orderItemId, node, Date.valueOf(planDate), employeeId,
                employeeName, quantity, sourceType, sourceId, sourceLineId, note, requestId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM production_plans WHERE plan_no = ?", Long.class, planNo);
    }

    public Optional<ProductionPlanRow> findById(long id) {
        var rows = jdbcTemplate.query("SELECT " + COLUMNS + " FROM production_plans WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 悲观锁定计划行：核验/取消前调用，保证「每计划最多一次有效核验」。 */
    public Optional<ProductionPlanRow> findByIdForUpdate(long id) {
        var rows = jdbcTemplate.query("SELECT " + COLUMNS + " FROM production_plans WHERE id = ? FOR UPDATE",
                MAPPER, id);
        return rows.stream().findFirst();
    }

    public void markVerified(long id, String requestId) {
        jdbcTemplate.update("""
                UPDATE production_plans SET status = 'VERIFIED', version = version + 1,
                    updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ?
                """, requestId, id);
    }

    /** 取消待执行计划：只改状态并保留取消人/时间/原因，原计划与来源关系不删除。 */
    public void markCancelled(long id, String cancelledBy, String reason, String requestId) {
        jdbcTemplate.update("""
                UPDATE production_plans SET status = 'CANCELLED', cancelled_at = UTC_TIMESTAMP(6),
                    cancelled_by = ?, cancel_reason = ?, version = version + 1,
                    updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ?
                """, cancelledBy, reason, requestId, id);
    }

    /** 按超额提醒调整待执行正常计划的数量（历史写入 `production_plan_adjustments`）。 */
    public void updateQuantity(long id, int quantity, String requestId) {
        jdbcTemplate.update("""
                UPDATE production_plans SET quantity = ?, version = version + 1,
                    updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ?
                """, quantity, requestId, id);
    }

    /** 列表筛选：日期区间、员工、工序、状态、订单、订单明细均可选。 */
    public List<ProductionPlanRow> find(LocalDate dateFrom, LocalDate dateTo, Long employeeId, String node,
                                        String status, Long orderId, Long orderItemId) {
        var sql = new StringBuilder("SELECT " + COLUMNS + " FROM production_plans WHERE 1=1");
        var args = new ArrayList<Object>();
        if (dateFrom != null) {
            sql.append(" AND plan_date >= ?");
            args.add(Date.valueOf(dateFrom));
        }
        if (dateTo != null) {
            sql.append(" AND plan_date <= ?");
            args.add(Date.valueOf(dateTo));
        }
        if (employeeId != null) {
            sql.append(" AND employee_id = ?");
            args.add(employeeId);
        }
        if (node != null && !node.isBlank()) {
            sql.append(" AND node = ?");
            args.add(node.trim());
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim());
        }
        if (orderId != null) {
            sql.append(" AND order_id = ?");
            args.add(orderId);
        }
        if (orderItemId != null) {
            sql.append(" AND order_item_id = ?");
            args.add(orderItemId);
        }
        sql.append(" ORDER BY plan_date, id");
        return jdbcTemplate.query(sql.toString(), MAPPER, args.toArray());
    }

    /**
     * 有效待执行计划占用：按 (order_item_id, node) 汇总 `status = PENDING` 的计划数量。
     * key 为 `orderItemId:node`，便于与核验汇总在服务层合并。
     */
    public Map<String, Integer> pendingByItemNode(List<Long> orderItemIds) {
        return aggregateByItemNode("""
                SELECT order_item_id, node, SUM(quantity) total FROM production_plans
                WHERE status = 'PENDING' AND order_item_id IN (%s)
                    AND plan_type IN ('NORMAL', 'REWORK', 'REMAKE', 'OVERTIME')
                GROUP BY order_item_id, node
                """, orderItemIds);
    }

    /** 已核验处理：按 (order_item_id, node) 汇总核验的本次完成数量（事实优先，不落投影）。 */
    public Map<String, Integer> verifiedByItemNode(List<Long> orderItemIds) {
        return aggregateByItemNode("""
                SELECT v.order_item_id, v.node, SUM(v.completed_quantity) total
                FROM production_verifications v JOIN production_plans p ON p.id = v.plan_id
                WHERE v.order_item_id IN (%s) AND p.plan_type IN ('NORMAL', 'REWORK', 'REMAKE', 'OVERTIME')
                GROUP BY v.order_item_id, v.node
                """, orderItemIds);
    }

    /** 该明细该工序的待执行计划，按「计划日期 + id」升序——当前可执行量按此顺序依次扣减。 */
    public List<ProductionPlanRow> pendingPlans(long orderItemId, String node) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM production_plans "
                + "WHERE order_item_id = ? AND node = ? AND status = 'PENDING' ORDER BY plan_date, id",
                MAPPER, orderItemId, node);
    }

    private Map<String, Integer> aggregateByItemNode(String template, List<Long> orderItemIds) {
        var result = new LinkedHashMap<String, Integer>();
        if (orderItemIds.isEmpty()) {
            return result;
        }
        var placeholders = String.join(",", java.util.Collections.nCopies(orderItemIds.size(), "?"));
        jdbcTemplate.query(String.format(template, placeholders), rs -> {
            result.put(rs.getLong("order_item_id") + ":" + rs.getString("node"), rs.getInt("total"));
        }, orderItemIds.toArray());
        return result;
    }

    private static ProductionPlanRow map(ResultSet rs, int rowNum) throws SQLException {
        return new ProductionPlanRow(rs.getLong("id"), rs.getString("plan_no"), rs.getString("plan_type"),
                rs.getLong("order_id"), rs.getLong("order_item_id"), rs.getString("node"),
                rs.getDate("plan_date").toLocalDate(), rs.getLong("employee_id"), rs.getString("employee_name"),
                rs.getInt("quantity"), rs.getString("status"), rs.getString("source_type"),
                rs.getLong("source_id"), rs.getLong("source_line_id"), rs.getString("note"),
                rs.getLong("version"));
    }
}

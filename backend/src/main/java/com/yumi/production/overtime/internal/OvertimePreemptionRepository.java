package com.yumi.production.overtime.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * `overtime_preemptions` 访问（阶段五）：超额任务对未来正常计划的业务预占。
 * 预占不修改未来计划原始数量、不产生工序流入或完成；核验后释放。
 * 「未来计划当前可选数量 = 计划数量 − 其他有效预占合计」，预占前按未来计划 id 升序加锁校验。
 */
@Repository
public class OvertimePreemptionRepository {

    public record PreemptionRow(long id, long overtimePlanId, long futurePlanId, long orderId, long orderItemId,
                               String node, int preemptedQuantity, String status) {
    }

    private static final String COLUMNS = """
            id, overtime_plan_id, future_plan_id, order_id, order_item_id, node, preempted_quantity, status
            """;

    private final JdbcTemplate jdbcTemplate;

    public OvertimePreemptionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(long overtimePlanId, long futurePlanId, long orderId, long orderItemId, String node,
                       int quantity, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO overtime_preemptions (overtime_plan_id, future_plan_id, order_id, order_item_id,
                    node, preempted_quantity, status, created_at, updated_at, request_id)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """, overtimePlanId, futurePlanId, orderId, orderItemId, node, quantity, requestId);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM overtime_preemptions WHERE overtime_plan_id = ? AND future_plan_id = ?
                """, Long.class, overtimePlanId, futurePlanId);
    }

    public List<PreemptionRow> findByOvertimePlan(long overtimePlanId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM overtime_preemptions WHERE overtime_plan_id = ? "
                + "ORDER BY id", this::map, overtimePlanId);
    }

    public Optional<PreemptionRow> findByIdForUpdate(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM overtime_preemptions WHERE id = ? FOR UPDATE",
                this::map, id).stream().findFirst();
    }

    /**
     * 某未来计划上的有效预占合计，**以锁定读方式**读取。
     * 必须用锁定读：MySQL REPEATABLE READ 下普通 SELECT 走事务首次读建立的快照，
     * 并发超额任务会看不到对方刚提交的预占而双双通过校验（实测复现）。
     * 同时它对该未来计划的预占区间加锁，阻止并发插入。
     */
    public int activeQuantity(long futurePlanId) {
        var rows = jdbcTemplate.queryForList("""
                SELECT preempted_quantity FROM overtime_preemptions
                WHERE future_plan_id = ? AND status = 'ACTIVE' FOR UPDATE
                """, Integer.class, futurePlanId);
        return rows.stream().mapToInt(Integer::intValue).sum();
    }

    /** 按未来计划 id 升序加锁，保证并发预占不超可选数量。 */
    public void lockFuturePlans(List<Long> futurePlanIds) {
        if (futurePlanIds.isEmpty()) {
            return;
        }
        var placeholders = String.join(",", java.util.Collections.nCopies(futurePlanIds.size(), "?"));
        jdbcTemplate.query("SELECT id FROM production_plans WHERE id IN (" + placeholders + ") ORDER BY id FOR UPDATE",
                rs -> {
                }, futurePlanIds.toArray());
    }

    /** 释放该超额任务的全部有效预占（核验后调用）。 */
    public int releaseAll(long overtimePlanId, String reason, String operatorUsername, String requestId) {
        return jdbcTemplate.update("""
                UPDATE overtime_preemptions SET status = 'RELEASED', released_at = UTC_TIMESTAMP(6),
                    released_by = ?, release_reason = ?, updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE overtime_plan_id = ? AND status = 'ACTIVE'
                """, operatorUsername, reason, requestId, overtimePlanId);
    }

    private PreemptionRow map(ResultSet rs, int rowNum) throws SQLException {
        return new PreemptionRow(rs.getLong("id"), rs.getLong("overtime_plan_id"), rs.getLong("future_plan_id"),
                rs.getLong("order_id"), rs.getLong("order_item_id"), rs.getString("node"),
                rs.getInt("preempted_quantity"), rs.getString("status"));
    }
}

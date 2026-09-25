package com.yumi.orders.fulfillment.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;

/**
 * 履约台账写入（任务 3.4；阶段四–六继续复用）：
 * {@code fulfillment_entries} 是不可变事实，只追加；{@code order_item_fulfillment_balances} 是当前投影，
 * 只允许在写事实的同一事务内更新。
 */
@Repository
public class FulfillmentRepository {

    private final JdbcTemplate jdbcTemplate;

    public FulfillmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 追加一条履约事实；同一来源（类型+记录+明细+节点+方向）重复入账由唯一键拒绝。 */
    public void insertEntry(long orderId, long orderItemId, String entryType, String node, String direction,
                            int quantity, String sourceType, long sourceId, long sourceLineId,
                            LocalDate businessDate, String operatorUsername, String note, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO fulfillment_entries (order_id, order_item_id, entry_type, node, direction, quantity,
                    source_type, source_id, source_line_id, business_date, operator_username, note,
                    created_at, updated_at, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """,
                orderId, orderItemId, entryType, node, direction, quantity,
                sourceType, sourceId, sourceLineId, Date.valueOf(businessDate), operatorUsername, note, requestId);
    }

    /** 追加一条履约事实并返回其 id（供领用/生产登记来源关联）。 */
    public long insertEntryReturningId(long orderId, long orderItemId, String entryType, String node,
                                       String direction, int quantity, String sourceType, long sourceId,
                                       long sourceLineId, LocalDate businessDate, String operatorUsername,
                                       String note, String requestId) {
        insertEntry(orderId, orderItemId, entryType, node, direction, quantity, sourceType, sourceId,
                sourceLineId, businessDate, operatorUsername, note, requestId);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM fulfillment_entries WHERE order_item_id = ? AND source_type = ? AND source_id = ?
                    AND source_line_id = ? AND node = ? AND direction = ?
                """, Long.class, orderItemId, sourceType, sourceId, sourceLineId, node, direction);
    }

    /**
     * 按目标节点调整投影：工序流入（捏毛装袋/缝边剪袋/制作）或最终可发货。
     * 只允许领域服务在写事实的同一事务内调用；数量不会为负（由调用方保证）。
     */
    public void applyInflow(long orderItemId, String node, int quantity, boolean increase, String requestId) {
        var column = switch (node) {
            case "MAKING" -> "making_inflow";
            case "PACKING_BAG" -> "packing_inflow";
            case "SEAM_CUTTING" -> "seam_inflow";
            default -> "shippable_quantity";
        };
        var sign = increase ? "+" : "-";
        jdbcTemplate.update("UPDATE order_item_fulfillment_balances SET " + column + " = " + column + " "
                + sign + " ?, version = version + 1, updated_at = UTC_TIMESTAMP(6), request_id = ? "
                + "WHERE order_item_id = ?", quantity, requestId, orderItemId);
    }

    /**
     * 该订单明细在指定履约事实之后是否存在下游消费事实（生产核验、返工、重做、成品余量、发货）。
     * 领用取消与流水冲销据此判断“是否已被后续事实消费”。
     */
    public boolean hasDownstreamConsumption(long orderItemId, long afterEntryId) {
        var count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fulfillment_entries
                WHERE order_item_id = ? AND id > ?
                  AND entry_type IN ('PRODUCTION_QUALIFIED', 'REWORK_IN', 'REMAKE_IN',
                                     'FINISHED_SURPLUS', 'SHIPMENT_CONSUME')
                """, Integer.class, orderItemId, afterEntryId);
        return count != null && count > 0;
    }

    /** 明细首次确认时建立投影行：只写当前有效需求，其余列由后续阶段写入。 */
    public void insertBalance(long orderId, long orderItemId, int requiredQuantity, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO order_item_fulfillment_balances (order_id, order_item_id, required_quantity,
                    version, created_at, updated_at, request_id)
                VALUES (?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """, orderId, orderItemId, requiredQuantity, requestId);
    }

    /** 变更确认后同步当前有效需求（投影只由领域服务在写事实的同一事务内更新）。 */
    public void updateRequiredQuantity(long orderItemId, int requiredQuantity, String requestId) {
        jdbcTemplate.update("""
                UPDATE order_item_fulfillment_balances
                SET required_quantity = ?, version = version + 1, updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE order_item_id = ?
                """, requiredQuantity, requestId, orderItemId);
    }

    public Integer requiredQuantity(long orderItemId) {
        return jdbcTemplate.query(
                "SELECT required_quantity FROM order_item_fulfillment_balances WHERE order_item_id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, orderItemId);
    }

    /** 明细数量投影（阶段三只有需求列有值，其余列由阶段四–六写入）。 */
    public record BalanceRow(long orderItemId, int requiredQuantity, int makingInflow, int packingInflow,
                             int seamInflow, int makingPlanned, int packingPlanned, int seamPlanned,
                             int verifiedProcessed, int reworkPending, int remakePending,
                             int shippableQuantity, int shippedQuantity, int finishedSurplusQuantity) {
    }

    public java.util.List<BalanceRow> findBalances(long orderId) {
        return jdbcTemplate.query("""
                SELECT order_item_id, required_quantity, making_inflow, packing_inflow, seam_inflow,
                       making_planned, packing_planned, seam_planned, verified_processed, rework_pending,
                       remake_pending, shippable_quantity, shipped_quantity, finished_surplus_quantity
                FROM order_item_fulfillment_balances WHERE order_id = ?
                """, (rs, rowNum) -> new BalanceRow(rs.getLong("order_item_id"),
                rs.getInt("required_quantity"), rs.getInt("making_inflow"), rs.getInt("packing_inflow"),
                rs.getInt("seam_inflow"), rs.getInt("making_planned"), rs.getInt("packing_planned"),
                rs.getInt("seam_planned"), rs.getInt("verified_processed"), rs.getInt("rework_pending"),
                rs.getInt("remake_pending"), rs.getInt("shippable_quantity"), rs.getInt("shipped_quantity"),
                rs.getInt("finished_surplus_quantity")), orderId);
    }

    /** 订单级派生状态输入：按订单汇总投影列（列表页一次查询即可）。 */
    public java.util.Map<Long, com.yumi.orders.fulfillment.OrderStatuses.Quantities> summarizeByOrder() {
        var result = new java.util.LinkedHashMap<Long, com.yumi.orders.fulfillment.OrderStatuses.Quantities>();
        jdbcTemplate.query("""
                SELECT order_id, SUM(required_quantity) required_quantity, SUM(shipped_quantity) shipped_quantity,
                       SUM(making_inflow) making_inflow, SUM(packing_inflow) packing_inflow,
                       SUM(seam_inflow) seam_inflow, SUM(making_planned) making_planned,
                       SUM(packing_planned) packing_planned, SUM(seam_planned) seam_planned,
                       SUM(verified_processed) verified_processed,
                       SUM(finished_surplus_quantity) finished_surplus_quantity
                FROM order_item_fulfillment_balances GROUP BY order_id
                """, rs -> {
            result.put(rs.getLong("order_id"), new com.yumi.orders.fulfillment.OrderStatuses.Quantities(
                    rs.getInt("required_quantity"), rs.getInt("shipped_quantity"),
                    rs.getInt("making_inflow"), rs.getInt("packing_inflow"), rs.getInt("seam_inflow"),
                    rs.getInt("making_planned"), rs.getInt("packing_planned"), rs.getInt("seam_planned"),
                    rs.getInt("verified_processed"), rs.getInt("finished_surplus_quantity")));
        });
        return result;
    }

    /**
     * 执行类履约事实数量：需求与变更事实是需求基线，不算“已产生履约”，
     * 取消订单只统计领用/生产/返工/重做/余量/发货等执行事实。
     */
    public int countExecutionFacts(long orderId) {
        var count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fulfillment_entries
                WHERE order_id = ? AND entry_type NOT IN ('ORDER_DEMAND', 'ORDER_CHANGE')
                """, Integer.class, orderId);
        return count == null ? 0 : count;
    }

    public java.util.List<java.util.Map<String, Object>> findEntries(long orderId) {
        return jdbcTemplate.queryForList("""
                SELECT entry_type, node, direction, quantity, source_type, source_id, source_line_id,
                       business_date, operator_username, reverses_entry_id, note
                FROM fulfillment_entries WHERE order_id = ? ORDER BY id
                """, orderId);
    }
}

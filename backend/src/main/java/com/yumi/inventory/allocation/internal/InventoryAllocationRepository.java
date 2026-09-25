package com.yumi.inventory.allocation.internal;

import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * inventory_allocations / inventory_allocation_lines 访问。
 * 领用只追加；取消只写状态与取消信息（原领用明细与流水保持不可变），反向事实由 4.9 追加。
 */
@Repository
public class InventoryAllocationRepository {

    private static final String ALLOCATION_COLUMNS = """
            id, order_id, status, reason, cancelled_at, cancelled_by, cancel_reason,
            reverses_allocation_id, version
            """;

    private static final String LINE_COLUMNS = """
            id, allocation_id, batch_id, order_item_id, quantity, target_node, movement_line_id,
            fulfillment_entry_id, reverses_line_id
            """;

    private static final RowMapper<InventoryAllocationRow> ALLOCATION_MAPPER = InventoryAllocationRepository::mapAllocation;
    private static final RowMapper<InventoryAllocationLineRow> LINE_MAPPER = InventoryAllocationRepository::mapLine;

    private final JdbcTemplate jdbcTemplate;

    public InventoryAllocationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<InventoryAllocationRow> findById(long id) {
        var rows = jdbcTemplate.query(
                "SELECT " + ALLOCATION_COLUMNS + " FROM inventory_allocations WHERE id = ?",
                ALLOCATION_MAPPER, id);
        return rows.stream().findFirst();
    }

    public List<InventoryAllocationRow> findByOrder(long orderId) {
        return jdbcTemplate.query("SELECT " + ALLOCATION_COLUMNS + " FROM inventory_allocations "
                + "WHERE order_id = ? ORDER BY id DESC", ALLOCATION_MAPPER, orderId);
    }

    public List<InventoryAllocationLineRow> findLines(long allocationId) {
        return jdbcTemplate.query("SELECT " + LINE_COLUMNS + " FROM inventory_allocation_lines "
                + "WHERE allocation_id = ? ORDER BY id", LINE_MAPPER, allocationId);
    }

    public java.util.Optional<InventoryAllocationLineRow> findLineByMovementLine(long movementLineId) {
        var rows = jdbcTemplate.query("SELECT " + LINE_COLUMNS + " FROM inventory_allocation_lines "
                + "WHERE movement_line_id = ?", LINE_MAPPER, movementLineId);
        return rows.stream().findFirst();
    }

    public long insertAllocation(InventoryAllocationRow row, String requestId, String idempotencyKey) {
        jdbcTemplate.update("""
                INSERT INTO inventory_allocations (order_id, status, reason, reverses_allocation_id,
                    version, created_at, updated_at, request_id, idempotency_key)
                VALUES (?, ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, ?)
                """,
                row.orderId(), row.status(), row.reason(), row.reversesAllocationId(), requestId, idempotencyKey);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM inventory_allocations WHERE order_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, row.orderId());
    }

    public long insertLine(InventoryAllocationLineRow row, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO inventory_allocation_lines (allocation_id, batch_id, order_item_id, quantity,
                    target_node, movement_line_id, fulfillment_entry_id, reverses_line_id,
                    created_at, updated_at, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """,
                row.allocationId(), row.batchId(), row.orderItemId(), row.quantity(), row.targetNode(),
                row.movementLineId(), row.fulfillmentEntryId(), row.reversesLineId(), requestId);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM inventory_allocation_lines WHERE allocation_id = ? ORDER BY id DESC LIMIT 1
                """, Long.class, row.allocationId());
    }

    /** 标记取消：只写状态与取消信息，原领用明细保持不可变。 */
    public int markCancelled(long allocationId, long expectedVersion, String cancelledBy, String reason,
                             String requestId) {
        return jdbcTemplate.update("""
                UPDATE inventory_allocations SET status = 'CANCELLED', cancelled_at = UTC_TIMESTAMP(6),
                    cancelled_by = ?, cancel_reason = ?, version = version + 1, updated_at = UTC_TIMESTAMP(6),
                    request_id = ?
                WHERE id = ? AND version = ?
                """, cancelledBy, reason, requestId, allocationId, expectedVersion);
    }

    /** 该领用是否已被后续取消（用于重复取消与冲销的守卫）。 */
    public boolean hasReversal(long allocationId) {
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_allocations WHERE reverses_allocation_id = ?",
                Integer.class, allocationId);
        return count != null && count > 0;
    }

    private static InventoryAllocationRow mapAllocation(ResultSet rs, int rowNum) throws SQLException {
        var cancelledAt = rs.getTimestamp("cancelled_at");
        long reverses = rs.getLong("reverses_allocation_id");
        boolean reversesNull = rs.wasNull();
        return new InventoryAllocationRow(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getString("status"),
                rs.getString("reason"),
                cancelledAt == null ? null : cancelledAt.toLocalDateTime(),
                rs.getString("cancelled_by"),
                rs.getString("cancel_reason"),
                reversesNull ? null : reverses,
                rs.getLong("version"));
    }

    private static InventoryAllocationLineRow mapLine(ResultSet rs, int rowNum) throws SQLException {
        long reverses = rs.getLong("reverses_line_id");
        boolean reversesNull = rs.wasNull();
        return new InventoryAllocationLineRow(
                rs.getLong("id"),
                rs.getLong("allocation_id"),
                rs.getLong("batch_id"),
                rs.getLong("order_item_id"),
                rs.getInt("quantity"),
                rs.getString("target_node"),
                rs.getLong("movement_line_id"),
                rs.getLong("fulfillment_entry_id"),
                reversesNull ? null : reverses);
    }
}

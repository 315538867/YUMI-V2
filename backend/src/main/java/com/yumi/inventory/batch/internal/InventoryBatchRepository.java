package com.yumi.inventory.batch.internal;

import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * inventory_batches 访问：显式列读写。
 * 领用用 {@link #lockForAllocation} 按 商品 + 工序 + 缝边状态 稳定顺序（id 升序）悲观锁批次，
 * 保证并发领用同一批次时只有一个事务能扣减成功。
 */
@Repository
public class InventoryBatchRepository {

    private static final String COLUMNS = """
            id, batch_no, product_id, product_no, product_name, source_type, source_id, source_line_id,
            node, seam_state, quantity, inventory_date, note, version
            """;

    private static final RowMapper<InventoryBatchRow> MAPPER = InventoryBatchRepository::map;

    private final JdbcTemplate jdbcTemplate;

    public InventoryBatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<InventoryBatchRow> findById(long id) {
        var rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM inventory_batches WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 批次列表：商品/工序/缝边状态/是否含零库存可筛。 */
    public List<InventoryBatchRow> find(Long productId, String node, String seamState, boolean includeEmpty) {
        var sql = new StringBuilder("SELECT " + COLUMNS + " FROM inventory_batches WHERE 1=1");
        var args = new ArrayList<Object>();
        if (productId != null) {
            sql.append(" AND product_id = ?");
            args.add(productId);
        }
        if (node != null && !node.isBlank()) {
            sql.append(" AND node = ?");
            args.add(node.trim());
        }
        if (seamState != null && !seamState.isBlank()) {
            sql.append(" AND seam_state = ?");
            args.add(seamState.trim());
        }
        if (!includeEmpty) {
            sql.append(" AND quantity > 0");
        }
        sql.append(" ORDER BY id");
        return jdbcTemplate.query(sql.toString(), MAPPER, args.toArray());
    }

    /** 按商品 + 工序 + 缝边状态汇总当前数量（零库存默认不出现）。 */
    public List<java.util.Map<String, Object>> summarize(Long productId, boolean includeEmpty) {
        var sql = new StringBuilder("""
                SELECT b.product_id, b.product_no, b.product_name, b.node, b.seam_state,
                       SUM(b.quantity) AS quantity, COUNT(*) AS batch_count
                FROM inventory_batches b WHERE 1=1
                """);
        var args = new ArrayList<Object>();
        if (productId != null) {
            sql.append(" AND b.product_id = ?");
            args.add(productId);
        }
        sql.append(" GROUP BY b.product_id, b.product_no, b.product_name, b.node, b.seam_state");
        if (!includeEmpty) {
            sql.append(" HAVING SUM(b.quantity) > 0");
        }
        sql.append(" ORDER BY b.product_id, b.node, b.seam_state");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /** 推荐候选与领用锁定：兼容目标节点且余额大于 0，按 id 升序（FIFO）并加行锁。 */
    public List<InventoryBatchRow> lockForAllocation(long productId, String node, String seamState) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM inventory_batches "
                + "WHERE product_id = ? AND node = ? AND seam_state = ? AND quantity > 0 "
                + "ORDER BY id FOR UPDATE", MAPPER, productId, node, seamState);
    }

    /** 按显式选择的批次加行锁（id 升序，避免多行领用时死锁），并重新读取当前数量。 */
    public List<InventoryBatchRow> lockByIds(List<Long> batchIds) {
        if (batchIds.isEmpty()) {
            return List.of();
        }
        var placeholders = String.join(",", batchIds.stream().map(id -> "?").toList());
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM inventory_batches WHERE id IN ("
                + placeholders + ") ORDER BY id FOR UPDATE", MAPPER, batchIds.toArray());
    }

    public long insert(InventoryBatchRow row, String requestId, String idempotencyKey) {
        jdbcTemplate.update("""
                INSERT INTO inventory_batches (batch_no, product_id, product_no, product_name,
                    source_type, source_id, source_line_id, node, seam_state, quantity, inventory_date, note,
                    version, created_at, updated_at, request_id, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, ?)
                """,
                row.batchNo(), row.productId(), row.productNo(), row.productName(), row.sourceType(),
                row.sourceId(), row.sourceLineId(), row.node(), row.seamState(), row.quantity(),
                Date.valueOf(row.inventoryDate()), row.note(), requestId, idempotencyKey);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM inventory_batches WHERE batch_no = ?", Long.class, row.batchNo());
    }

    /** 按旧版本整体覆盖数量并递增版本；版本失配 → CONFLICT_VERSION。 */
    public void updateQuantity(InventoryBatchRow row, int quantity, String requestId) {
        int updated = jdbcTemplate.update("""
                UPDATE inventory_batches SET quantity = ?, version = version + 1,
                    updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ? AND version = ?
                """, quantity, requestId, row.id(), row.version());
        if (updated == 0) {
            throw new ApiException(ErrorCode.CONFLICT_VERSION);
        }
    }

    private static InventoryBatchRow map(ResultSet rs, int rowNum) throws SQLException {
        Date date = rs.getDate("inventory_date");
        return new InventoryBatchRow(
                rs.getLong("id"),
                rs.getString("batch_no"),
                rs.getLong("product_id"),
                rs.getString("product_no"),
                rs.getString("product_name"),
                rs.getString("source_type"),
                rs.getLong("source_id"),
                rs.getLong("source_line_id"),
                rs.getString("node"),
                rs.getString("seam_state"),
                rs.getInt("quantity"),
                date == null ? null : date.toLocalDate(),
                rs.getString("note"),
                rs.getLong("version"));
    }
}

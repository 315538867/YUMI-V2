package com.yumi.inventory.movement.internal;

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
 * inventory_movements / inventory_movement_lines 访问：流水与流水行只追加（不可变），
 * 更正一律新增冲销流水并保留关联历史。
 */
@Repository
public class InventoryMovementRepository {

    private static final String MOVEMENT_COLUMNS = """
            id, movement_no, movement_type, business_date, source_type, source_id, source_line_id,
            reverses_movement_id, reason, operator_username, note
            """;

    private static final String LINE_COLUMNS = """
            id, movement_id, batch_id, direction, quantity, quantity_before, quantity_after,
            product_id, node, seam_state, order_item_id, note
            """;

    private static final RowMapper<InventoryMovementRow> MOVEMENT_MAPPER = InventoryMovementRepository::mapMovement;
    private static final RowMapper<InventoryMovementLineRow> LINE_MAPPER = InventoryMovementRepository::mapLine;

    private final JdbcTemplate jdbcTemplate;

    public InventoryMovementRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<InventoryMovementRow> findById(long id) {
        var rows = jdbcTemplate.query(
                "SELECT " + MOVEMENT_COLUMNS + " FROM inventory_movements WHERE id = ?", MOVEMENT_MAPPER, id);
        return rows.stream().findFirst();
    }

    public List<InventoryMovementRow> find(String movementType, Long batchId, LocalDate from, LocalDate to) {
        var sql = new StringBuilder("SELECT m.id, m.movement_no, m.movement_type, m.business_date, "
                + "m.source_type, m.source_id, m.source_line_id, m.reverses_movement_id, m.reason, "
                + "m.operator_username, m.note FROM inventory_movements m");
        var args = new ArrayList<Object>();
        if (batchId != null) {
            sql.append(" JOIN inventory_movement_lines l ON l.movement_id = m.id AND l.batch_id = ?");
            args.add(batchId);
        }
        sql.append(" WHERE 1=1");
        if (movementType != null && !movementType.isBlank()) {
            sql.append(" AND m.movement_type = ?");
            args.add(movementType.trim());
        }
        if (from != null) {
            sql.append(" AND m.business_date >= ?");
            args.add(Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND m.business_date <= ?");
            args.add(Date.valueOf(to));
        }
        sql.append(" GROUP BY m.id ORDER BY m.id DESC");
        return jdbcTemplate.query(sql.toString(), MOVEMENT_MAPPER, args.toArray());
    }

    public List<InventoryMovementLineRow> findLines(Long movementId, Long batchId, Long orderItemId) {
        var sql = new StringBuilder("SELECT " + LINE_COLUMNS + " FROM inventory_movement_lines WHERE 1=1");
        var args = new ArrayList<Object>();
        if (movementId != null) {
            sql.append(" AND movement_id = ?");
            args.add(movementId);
        }
        if (batchId != null) {
            sql.append(" AND batch_id = ?");
            args.add(batchId);
        }
        if (orderItemId != null) {
            sql.append(" AND order_item_id = ?");
            args.add(orderItemId);
        }
        sql.append(" ORDER BY id");
        return jdbcTemplate.query(sql.toString(), LINE_MAPPER, args.toArray());
    }

    public java.util.Optional<InventoryMovementLineRow> findLineById(long id) {
        var rows = jdbcTemplate.query(
                "SELECT " + LINE_COLUMNS + " FROM inventory_movement_lines WHERE id = ?", LINE_MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 以本流水为冲销对象的流水（存在即表示已冲销）。 */
    public java.util.Optional<InventoryMovementRow> findReversalOf(long movementId) {
        var rows = jdbcTemplate.query("SELECT " + MOVEMENT_COLUMNS + " FROM inventory_movements "
                + "WHERE reverses_movement_id = ? ORDER BY id LIMIT 1", MOVEMENT_MAPPER, movementId);
        return rows.stream().findFirst();
    }

    /** 批次当前数量必须等于有效流水行汇总；供一致性校验与测试使用。 */
    public int rebuildBatchQuantity(long batchId) {
        var value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'IN' THEN quantity ELSE -quantity END), 0)
                FROM inventory_movement_lines WHERE batch_id = ?
                """, Integer.class, batchId);
        return value == null ? 0 : value;
    }

    public long insertMovement(InventoryMovementRow row, String requestId, String idempotencyKey) {
        jdbcTemplate.update("""
                INSERT INTO inventory_movements (movement_no, movement_type, business_date, source_type,
                    source_id, source_line_id, reverses_movement_id, reason, operator_username, note,
                    created_at, updated_at, request_id, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, ?)
                """,
                row.movementNo(), row.movementType(), Date.valueOf(row.businessDate()), row.sourceType(),
                row.sourceId(), row.sourceLineId(), row.reversesMovementId(), row.reason(),
                row.operatorUsername(), row.note(), requestId, idempotencyKey);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM inventory_movements WHERE movement_no = ?", Long.class, row.movementNo());
    }

    public long insertLine(InventoryMovementLineRow row, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO inventory_movement_lines (movement_id, batch_id, direction, quantity,
                    quantity_before, quantity_after, product_id, node, seam_state, order_item_id, note,
                    created_at, updated_at, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """,
                row.movementId(), row.batchId(), row.direction(), row.quantity(), row.quantityBefore(),
                row.quantityAfter(), row.productId(), row.node(), row.seamState(), row.orderItemId(),
                row.note(), requestId);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM inventory_movement_lines
                WHERE movement_id = ? AND batch_id = ? ORDER BY id DESC LIMIT 1
                """, Long.class, row.movementId(), row.batchId());
    }

    private static InventoryMovementRow mapMovement(ResultSet rs, int rowNum) throws SQLException {
        Date date = rs.getDate("business_date");
        long reverses = rs.getLong("reverses_movement_id");
        boolean reversesNull = rs.wasNull();
        return new InventoryMovementRow(
                rs.getLong("id"),
                rs.getString("movement_no"),
                rs.getString("movement_type"),
                date == null ? null : date.toLocalDate(),
                rs.getString("source_type"),
                rs.getLong("source_id"),
                rs.getLong("source_line_id"),
                reversesNull ? null : reverses,
                rs.getString("reason"),
                rs.getString("operator_username"),
                rs.getString("note"));
    }

    private static InventoryMovementLineRow mapLine(ResultSet rs, int rowNum) throws SQLException {
        long orderItemId = rs.getLong("order_item_id");
        boolean orderItemNull = rs.wasNull();
        return new InventoryMovementLineRow(
                rs.getLong("id"),
                rs.getLong("movement_id"),
                rs.getLong("batch_id"),
                rs.getString("direction"),
                rs.getInt("quantity"),
                rs.getInt("quantity_before"),
                rs.getInt("quantity_after"),
                rs.getLong("product_id"),
                rs.getString("node"),
                rs.getString("seam_state"),
                orderItemNull ? null : orderItemId,
                rs.getString("note"));
    }
}

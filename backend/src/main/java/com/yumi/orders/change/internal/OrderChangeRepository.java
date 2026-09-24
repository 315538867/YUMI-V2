package com.yumi.orders.change.internal;

import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * order_change_orders / order_change_items 访问：显式列读写；变更确认带乐观版本条件，
 * 失配映射为 CONFLICT_VERSION。
 */
@Repository
public class OrderChangeRepository {

    private static final String CHANGE_COLUMNS = """
            id, change_no, order_id, status, reason, new_expected_delivery_date, new_recipient_name,
            new_recipient_phone, new_region, new_address, new_note, new_discount_amount, version
            """;

    private static final String ITEM_COLUMNS = """
            id, change_order_id, order_item_id, change_type, line_no, product_id,
            before_quantity, after_quantity, before_seam_quantity, after_seam_quantity,
            before_unit_price, after_unit_price, before_seam_type_id, after_seam_type_id,
            before_seam_fee, after_seam_fee, before_note, after_note,
            surplus_disposition, surplus_quantity, surplus_reason
            """;

    private static final RowMapper<OrderChangeRow> CHANGE_MAPPER = OrderChangeRepository::mapChange;
    private static final RowMapper<OrderChangeItemRow> ITEM_MAPPER = OrderChangeRepository::mapItem;

    private final JdbcTemplate jdbcTemplate;

    public OrderChangeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<OrderChangeRow> findById(long id) {
        var rows = jdbcTemplate.query(
                "SELECT " + CHANGE_COLUMNS + " FROM order_change_orders WHERE id = ?", CHANGE_MAPPER, id);
        return rows.stream().findFirst();
    }

    public List<OrderChangeRow> findByOrder(long orderId) {
        return jdbcTemplate.query(
                "SELECT " + CHANGE_COLUMNS + " FROM order_change_orders WHERE order_id = ? ORDER BY id DESC",
                CHANGE_MAPPER, orderId);
    }

    /** 同一订单最多一个未确认变更草稿。 */
    public Optional<OrderChangeRow> findDraftByOrder(long orderId) {
        return findByOrder(orderId).stream().filter(row -> "DRAFT".equals(row.status())).findFirst();
    }

    public long insertChange(OrderChangeRow row, String requestId, String idempotencyKey) {
        jdbcTemplate.update("""
                INSERT INTO order_change_orders (change_no, order_id, status, reason,
                    new_expected_delivery_date, new_recipient_name, new_recipient_phone, new_region,
                    new_address, new_note, new_discount_amount, version, created_at, updated_at,
                    request_id, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, ?)
                """,
                row.changeNo(), row.orderId(), row.status(), row.reason(),
                row.newExpectedDeliveryDate() == null ? null : Date.valueOf(row.newExpectedDeliveryDate()),
                row.newRecipientName(), row.newRecipientPhone(), row.newRegion(), row.newAddress(),
                row.newNote(), row.newDiscountAmount(), requestId, idempotencyKey);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM order_change_orders WHERE change_no = ?", Long.class, row.changeNo());
    }

    public void updateChange(OrderChangeRow row, String requestId) {
        int updated = jdbcTemplate.update("""
                UPDATE order_change_orders SET reason = ?, new_expected_delivery_date = ?,
                    new_recipient_name = ?, new_recipient_phone = ?, new_region = ?, new_address = ?,
                    new_note = ?, new_discount_amount = ?, version = version + 1,
                    updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ? AND version = ?
                """,
                row.reason(),
                row.newExpectedDeliveryDate() == null ? null : Date.valueOf(row.newExpectedDeliveryDate()),
                row.newRecipientName(), row.newRecipientPhone(), row.newRegion(), row.newAddress(),
                row.newNote(), row.newDiscountAmount(), requestId, row.id(), row.version());
        if (updated == 0) {
            throw new ApiException(ErrorCode.CONFLICT_VERSION);
        }
    }

    public int markConfirmed(long changeId, long expectedVersion, String confirmedBy, String requestId) {
        return jdbcTemplate.update("""
                UPDATE order_change_orders SET status = 'CONFIRMED', confirmed_by = ?,
                    confirmed_at = UTC_TIMESTAMP(6), version = version + 1, updated_at = UTC_TIMESTAMP(6),
                    request_id = ?
                WHERE id = ? AND version = ?
                """, confirmedBy, requestId, changeId, expectedVersion);
    }

    public List<OrderChangeItemRow> findItems(long changeOrderId) {
        return jdbcTemplate.query("SELECT " + ITEM_COLUMNS + " FROM order_change_items "
                + "WHERE change_order_id = ? ORDER BY id", ITEM_MAPPER, changeOrderId);
    }

    public void deleteItems(long changeOrderId) {
        jdbcTemplate.update("DELETE FROM order_change_items WHERE change_order_id = ?", changeOrderId);
    }

    public void insertItem(OrderChangeItemRow row, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO order_change_items (change_order_id, order_item_id, change_type, line_no, product_id,
                    before_quantity, after_quantity, before_seam_quantity, after_seam_quantity,
                    before_unit_price, after_unit_price, before_seam_type_id, after_seam_type_id,
                    before_seam_fee, after_seam_fee, before_note, after_note,
                    surplus_disposition, surplus_quantity, surplus_reason, created_at, updated_at, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """,
                row.changeOrderId(), row.orderItemId(), row.changeType(), row.lineNo(), row.productId(),
                row.beforeQuantity(), row.afterQuantity(), row.beforeSeamQuantity(), row.afterSeamQuantity(),
                row.beforeUnitPrice(), row.afterUnitPrice(), row.beforeSeamTypeId(), row.afterSeamTypeId(),
                row.beforeSeamFee(), row.afterSeamFee(), row.beforeNote(), row.afterNote(),
                row.surplusDisposition(), row.surplusQuantity(), row.surplusReason(), requestId);
    }

    /** 草稿阶段的明细整体替换：先清后写，保持与草稿“可自由编辑”的语义一致。 */
    public void replaceItems(long changeOrderId, List<OrderChangeItemRow> rows, String requestId) {
        deleteItems(changeOrderId);
        for (var row : rows) {
            insertItem(new OrderChangeItemRow(null, changeOrderId, row.orderItemId(), row.changeType(),
                    row.lineNo(), row.productId(), row.beforeQuantity(), row.afterQuantity(),
                    row.beforeSeamQuantity(), row.afterSeamQuantity(), row.beforeUnitPrice(),
                    row.afterUnitPrice(), row.beforeSeamTypeId(), row.afterSeamTypeId(), row.beforeSeamFee(),
                    row.afterSeamFee(), row.beforeNote(), row.afterNote(), row.surplusDisposition(),
                    row.surplusQuantity(), row.surplusReason()), requestId);
        }
    }

    private static OrderChangeRow mapChange(ResultSet rs, int rowNum) throws SQLException {
        return new OrderChangeRow(
                rs.getLong("id"),
                rs.getString("change_no"),
                rs.getLong("order_id"),
                rs.getString("status"),
                rs.getString("reason"),
                toLocalDate(rs.getDate("new_expected_delivery_date")),
                rs.getString("new_recipient_name"),
                rs.getString("new_recipient_phone"),
                rs.getString("new_region"),
                rs.getString("new_address"),
                rs.getString("new_note"),
                rs.getBigDecimal("new_discount_amount"),
                rs.getLong("version"));
    }

    private static OrderChangeItemRow mapItem(ResultSet rs, int rowNum) throws SQLException {
        return new OrderChangeItemRow(
                rs.getLong("id"),
                rs.getLong("change_order_id"),
                nullableLong(rs, "order_item_id"),
                rs.getString("change_type"),
                nullableInt(rs, "line_no"),
                nullableLong(rs, "product_id"),
                nullableInt(rs, "before_quantity"),
                nullableInt(rs, "after_quantity"),
                nullableInt(rs, "before_seam_quantity"),
                nullableInt(rs, "after_seam_quantity"),
                rs.getBigDecimal("before_unit_price"),
                rs.getBigDecimal("after_unit_price"),
                nullableLong(rs, "before_seam_type_id"),
                nullableLong(rs, "after_seam_type_id"),
                rs.getBigDecimal("before_seam_fee"),
                rs.getBigDecimal("after_seam_fee"),
                rs.getString("before_note"),
                rs.getString("after_note"),
                rs.getString("surplus_disposition"),
                nullableInt(rs, "surplus_quantity"),
                rs.getString("surplus_reason"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }
}

package com.yumi.orders.shipment.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * `shipments` / `shipment_items` / `shipment_source_links` / `shipment_logistics_changes`
 * 访问（阶段六）：显式列读写。
 * 发货批次与明细同属 orders 模块，可直接读履约投影与订单明细。
 */
@Repository
public class ShipmentRepository {

    public record ShipmentRow(long id, String shipmentNo, long orderId, String status, LocalDate shipmentDate,
                              String carrier, String trackingNo, BigDecimal freight, String logisticsNote,
                              String currentCarrier, String currentTrackingNo, BigDecimal currentFreight,
                              String currentLogisticsNote, String note, String confirmedBy, String voidReason,
                              Long replacesShipmentId, long version) {
    }

    public record ShipmentItemRow(long id, long shipmentId, long orderItemId, int lineNo, int quantity,
                                  String productNo, String productName, String recipientName,
                                  String recipientPhone, String region, String address,
                                  Integer cumulativeShippedQuantity, Integer undeliveredQuantity) {
    }

    public record SourceLinkRow(long id, long shipmentItemId, String sourceType, long sourceId,
                                long sourceLineId, int quantity) {
    }

    public record LogisticsChangeRow(long id, long shipmentId, String beforeCarrier, String afterCarrier,
                                     String beforeTrackingNo, String afterTrackingNo, BigDecimal beforeFreight,
                                     BigDecimal afterFreight, String beforeNote, String afterNote, String reason) {
    }

    /** 可发货流入来源（用于发货来源追溯）：按事实 id 升序贪心分配。 */
    public record InflowEntry(long entryId, String entryType, long sourceId, int quantity) {
    }

    private static final String COLUMNS = """
            id, shipment_no, order_id, status, shipment_date, carrier, tracking_no, freight, logistics_note,
            current_carrier, current_tracking_no, current_freight, current_logistics_note, note, confirmed_by,
            void_reason, replaces_shipment_id, version
            """;

    private final JdbcTemplate jdbcTemplate;

    public ShipmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(String shipmentNo, long orderId, LocalDate shipmentDate, String carrier, String trackingNo,
                       BigDecimal freight, String logisticsNote, String note, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO shipments (shipment_no, order_id, status, shipment_date, carrier, tracking_no, freight,
                    logistics_note, current_carrier, current_tracking_no, current_freight, current_logistics_note,
                    note, version, created_at, updated_at, request_id)
                VALUES (?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """, shipmentNo, orderId, Date.valueOf(shipmentDate), carrier, trackingNo, freight, logisticsNote,
                carrier, trackingNo, freight, logisticsNote, note, requestId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM shipments WHERE shipment_no = ?", Long.class, shipmentNo);
    }

    public Optional<ShipmentRow> findById(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM shipments WHERE id = ?", this::mapShipment, id)
                .stream().findFirst();
    }

    public Optional<ShipmentRow> findByIdForUpdate(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM shipments WHERE id = ? FOR UPDATE",
                this::mapShipment, id).stream().findFirst();
    }

    /**
     * 该发货批次是否被售后占用（其明细是某个售后单的来源）：
     * 作废会减少有效已发数量，使既有售后受理量失去依据，故必须拒绝（`SHIPMENT_AFTER_SALES_LINKED`）。
     */
    public boolean hasAfterSalesOccupancy(long shipmentId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM after_sales_items i JOIN shipment_items si ON si.id = i.shipment_item_id
                               WHERE si.shipment_id = ?)
                """, Boolean.class, shipmentId));
    }

    /** 该发货批次是否为售后补发批次（存在售后补发关联）：售后补发品不能作为售后来源，也不应与订单发货批次混同。 */
    public boolean isAfterSalesReplacement(long shipmentId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM after_sales_shipment_links WHERE shipment_id = ?)",
                Boolean.class, shipmentId));
    }

    public List<ShipmentRow> findByOrder(long orderId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM shipments WHERE order_id = ? ORDER BY id",
                this::mapShipment, orderId);
    }

    public void updateDraft(long id, LocalDate shipmentDate, String carrier, String trackingNo, BigDecimal freight,
                            String logisticsNote, String note, String requestId) {
        jdbcTemplate.update("""
                UPDATE shipments SET shipment_date = ?, carrier = ?, tracking_no = ?, freight = ?,
                    logistics_note = ?, current_carrier = ?, current_tracking_no = ?, current_freight = ?,
                    current_logistics_note = ?, note = ?, version = version + 1,
                    updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ?
                """, Date.valueOf(shipmentDate), carrier, trackingNo, freight, logisticsNote, carrier, trackingNo,
                freight, logisticsNote, note, requestId, id);
    }

    public void deleteItems(long shipmentId) {
        jdbcTemplate.update("DELETE FROM shipment_source_links WHERE shipment_item_id IN "
                + "(SELECT id FROM shipment_items WHERE shipment_id = ?)", shipmentId);
        jdbcTemplate.update("DELETE FROM shipment_items WHERE shipment_id = ?", shipmentId);
    }

    public long insertItem(long shipmentId, long orderItemId, int lineNo, int quantity, String productNo,
                           String productName, String recipientName, String recipientPhone, String region,
                           String address, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO shipment_items (shipment_id, order_item_id, line_no, quantity, product_no,
                    product_name, recipient_name, recipient_phone, region, address, version, created_at,
                    updated_at, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """, shipmentId, orderItemId, lineNo, quantity, productNo, productName, recipientName,
                recipientPhone, region, address, requestId);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM shipment_items WHERE shipment_id = ? AND order_item_id = ?
                """, Long.class, shipmentId, orderItemId);
    }

    public List<ShipmentItemRow> findItems(long shipmentId) {
        return jdbcTemplate.query("""
                SELECT id, shipment_id, order_item_id, line_no, quantity, product_no, product_name,
                       recipient_name, recipient_phone, region, address, cumulative_shipped_quantity,
                       undelivered_quantity
                FROM shipment_items WHERE shipment_id = ? ORDER BY line_no
                """, this::mapItem, shipmentId);
    }

    /** 确认时快照：本次之后的累计有效发货与未交付需求。 */
    public void markItemConfirmed(long shipmentItemId, int cumulativeShipped, int undelivered, String requestId) {
        jdbcTemplate.update("""
                UPDATE shipment_items SET cumulative_shipped_quantity = ?, undelivered_quantity = ?,
                    version = version + 1, updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ?
                """, cumulativeShipped, undelivered, requestId, shipmentItemId);
    }

    public void markConfirmed(long id, String confirmedBy, String requestId) {
        jdbcTemplate.update("""
                UPDATE shipments SET status = 'CONFIRMED', confirmed_at = UTC_TIMESTAMP(6), confirmed_by = ?,
                    version = version + 1, updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ?
                """, confirmedBy, requestId, id);
    }

    public void markVoided(long id, String voidedBy, String reason, String requestId) {
        jdbcTemplate.update("""
                UPDATE shipments SET status = 'VOIDED', voided_at = UTC_TIMESTAMP(6), voided_by = ?,
                    void_reason = ?, version = version + 1, updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ?
                """, voidedBy, reason, requestId, id);
    }

    public void markReplaces(long id, long replacesShipmentId, String requestId) {
        jdbcTemplate.update("""
                UPDATE shipments SET replaces_shipment_id = ?, version = version + 1,
                    updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ?
                """, replacesShipmentId, requestId, id);
    }

    public void updateCurrentLogistics(long id, String carrier, String trackingNo, BigDecimal freight,
                                       String logisticsNote, String requestId) {
        jdbcTemplate.update("""
                UPDATE shipments SET current_carrier = ?, current_tracking_no = ?, current_freight = ?,
                    current_logistics_note = ?, version = version + 1, updated_at = UTC_TIMESTAMP(6),
                    request_id = ?
                WHERE id = ?
                """, carrier, trackingNo, freight, logisticsNote, requestId, id);
    }

    public long insertLogisticsChange(long shipmentId, String beforeCarrier, String afterCarrier,
                                      String beforeTrackingNo, String afterTrackingNo, BigDecimal beforeFreight,
                                      BigDecimal afterFreight, String beforeNote, String afterNote, String reason,
                                      String requestId) {
        jdbcTemplate.update("""
                INSERT INTO shipment_logistics_changes (shipment_id, before_carrier, after_carrier,
                    before_tracking_no, after_tracking_no, before_freight, after_freight, before_note, after_note,
                    reason, created_at, updated_at, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """, shipmentId, beforeCarrier, afterCarrier, beforeTrackingNo, afterTrackingNo, beforeFreight,
                afterFreight, beforeNote, afterNote, reason, requestId);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM shipment_logistics_changes WHERE shipment_id = ? ORDER BY id DESC LIMIT 1
                """, Long.class, shipmentId);
    }

    public List<LogisticsChangeRow> findLogisticsChanges(long shipmentId) {
        return jdbcTemplate.query("""
                SELECT id, shipment_id, before_carrier, after_carrier, before_tracking_no, after_tracking_no,
                       before_freight, after_freight, before_note, after_note, reason
                FROM shipment_logistics_changes WHERE shipment_id = ? ORDER BY id
                """, (rs, rowNum) -> new LogisticsChangeRow(rs.getLong("id"), rs.getLong("shipment_id"),
                rs.getString("before_carrier"), rs.getString("after_carrier"),
                rs.getString("before_tracking_no"), rs.getString("after_tracking_no"),
                rs.getBigDecimal("before_freight"), rs.getBigDecimal("after_freight"),
                rs.getString("before_note"), rs.getString("after_note"), rs.getString("reason")), shipmentId);
    }

    public long insertSourceLink(long shipmentItemId, String sourceType, long sourceId, long sourceLineId,
                                 int quantity, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO shipment_source_links (shipment_item_id, source_type, source_id, source_line_id,
                    quantity, created_at, updated_at, request_id)
                VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """, shipmentItemId, sourceType, sourceId, sourceLineId, quantity, requestId);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM shipment_source_links WHERE shipment_item_id = ? AND source_type = ?
                    AND source_id = ? AND source_line_id = ?
                """, Long.class, shipmentItemId, sourceType, sourceId, sourceLineId);
    }

    public List<SourceLinkRow> findSourceLinks(long shipmentItemId) {
        return jdbcTemplate.query("""
                SELECT id, shipment_item_id, source_type, source_id, source_line_id, quantity
                FROM shipment_source_links WHERE shipment_item_id = ? ORDER BY id
                """, (rs, rowNum) -> new SourceLinkRow(rs.getLong("id"), rs.getLong("shipment_item_id"),
                rs.getString("source_type"), rs.getLong("source_id"), rs.getLong("source_line_id"),
                rs.getInt("quantity")), shipmentItemId);
    }

    /** 该明细可发货节点的入库来源事实（库存领用接入、生产合格），按事实 id 升序。 */
    public List<InflowEntry> findShippableInflows(long orderItemId) {
        return jdbcTemplate.query("""
                SELECT id, entry_type, source_id, quantity FROM fulfillment_entries
                WHERE order_item_id = ? AND node = 'SHIPPABLE' AND direction = 'IN'
                  AND entry_type IN ('INVENTORY_ALLOCATION', 'PRODUCTION_QUALIFIED')
                ORDER BY id
                """, (rs, rowNum) -> new InflowEntry(rs.getLong("id"), rs.getString("entry_type"),
                rs.getLong("source_id"), rs.getInt("quantity")), orderItemId);
    }

    public boolean existsCorrectionFor(long originalShipmentId) {
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shipment_corrections WHERE original_shipment_id = ?", Integer.class,
                originalShipmentId);
        return count != null && count > 0;
    }

    public long insertCorrection(long originalShipmentId, long replacementShipmentId, String reason,
                                 String requestId) {
        jdbcTemplate.update("""
                INSERT INTO shipment_corrections (original_shipment_id, replacement_shipment_id, reason,
                    created_at, updated_at, request_id)
                VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """, originalShipmentId, replacementShipmentId, reason, requestId);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM shipment_corrections WHERE original_shipment_id = ?
                """, Long.class, originalShipmentId);
    }

    /** 订单明细的当前投影（可发货/累计发货/当前有效需求/已核验处理），用于确认与作废校验。 */
    public record ItemBalanceRow(long orderItemId, int requiredQuantity, int shippableQuantity,
                                 int shippedQuantity) {
    }

    public List<ItemBalanceRow> findBalances(long orderId) {
        return jdbcTemplate.query("""
                SELECT order_item_id, required_quantity, shippable_quantity, shipped_quantity
                FROM order_item_fulfillment_balances WHERE order_id = ? ORDER BY order_item_id
                """, (rs, rowNum) -> new ItemBalanceRow(rs.getLong("order_item_id"), rs.getInt("required_quantity"),
                rs.getInt("shippable_quantity"), rs.getInt("shipped_quantity")), orderId);
    }

    public Optional<ItemBalanceRow> findBalanceForUpdate(long orderItemId) {
        return jdbcTemplate.query("""
                SELECT order_item_id, required_quantity, shippable_quantity, shipped_quantity
                FROM order_item_fulfillment_balances WHERE order_item_id = ? FOR UPDATE
                """, (rs, rowNum) -> new ItemBalanceRow(rs.getLong("order_item_id"), rs.getInt("required_quantity"),
                rs.getInt("shippable_quantity"), rs.getInt("shipped_quantity")), orderItemId).stream().findFirst();
    }

    public List<Long> findOrderItemIds(long orderId) {
        var ids = new ArrayList<Long>();
        jdbcTemplate.query("SELECT id FROM order_items WHERE order_id = ? ORDER BY id", rs -> {
            ids.add(rs.getLong(1));
        }, orderId);
        return ids;
    }

    private ShipmentRow mapShipment(ResultSet rs, int rowNum) throws SQLException {
        // wasNull() 只反映「最近一次读取」，必须紧跟对应列读取后立即判定，否则会被后续列覆盖
        long replaces = rs.getLong("replaces_shipment_id");
        boolean replacesNull = rs.wasNull();
        return new ShipmentRow(rs.getLong("id"), rs.getString("shipment_no"), rs.getLong("order_id"),
                rs.getString("status"), rs.getDate("shipment_date").toLocalDate(), rs.getString("carrier"),
                rs.getString("tracking_no"), rs.getBigDecimal("freight"), rs.getString("logistics_note"),
                rs.getString("current_carrier"), rs.getString("current_tracking_no"),
                rs.getBigDecimal("current_freight"), rs.getString("current_logistics_note"),
                rs.getString("note"), rs.getString("confirmed_by"), rs.getString("void_reason"),
                replacesNull ? null : replaces, rs.getLong("version"));
    }

    private ShipmentItemRow mapItem(ResultSet rs, int rowNum) throws SQLException {
        int cumulative = rs.getInt("cumulative_shipped_quantity");
        boolean cumulativeNull = rs.wasNull();
        int undelivered = rs.getInt("undelivered_quantity");
        boolean undeliveredNull = rs.wasNull();
        return new ShipmentItemRow(rs.getLong("id"), rs.getLong("shipment_id"), rs.getLong("order_item_id"),
                rs.getInt("line_no"), rs.getInt("quantity"), rs.getString("product_no"),
                rs.getString("product_name"), rs.getString("recipient_name"), rs.getString("recipient_phone"),
                rs.getString("region"), rs.getString("address"), cumulativeNull ? null : cumulative,
                undeliveredNull ? null : undelivered);
    }
}

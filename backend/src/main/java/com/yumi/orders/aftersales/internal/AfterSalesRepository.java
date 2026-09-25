package com.yumi.orders.aftersales.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 售后表访问（阶段八）：显式列读写。
 * 来源校验用**锁定读**读取原发货批次明细（`shipment_items` JOIN `shipments`），
 * 保证并发受理不超「剩余可受理量」；可补发/已补发一律从售后台账汇总，不落缓存列。
 */
@Repository
public class AfterSalesRepository {

    public record CaseRow(long id, String caseNo, long orderId, String caseType, String status, String problem,
                          String solution, String note) {
    }

    public record ItemRow(long id, long caseId, long orderId, long orderItemId, long shipmentItemId,
                          String productNo, String productName, int seamQuantity, int acceptedQuantity,
                          int returnedQuantity, int replacementRequiredQuantity, Long returnVerificationId) {
    }

    public record ReturnVerificationRow(long id, long afterSalesItemId, int returnedQuantity, int reworkQuantity,
                                        int scrapQuantity, String reason, String verifiedBy) {
    }

    /** 原发货批次明细的只读上下文（用于来源校验与展示）。 */
    public record SourceRow(long shipmentItemId, long shipmentId, String shipmentNo, String shipmentStatus,
                            long orderItemId, int quantity, String productNo, String productName,
                            boolean afterSalesReplacement) {
    }

    private static final String CASE_COLUMNS = "id, case_no, order_id, case_type, status, problem, solution, note";
    private static final String ITEM_COLUMNS = """
            id, case_id, order_id, order_item_id, shipment_item_id, product_no, product_name, seam_quantity,
            accepted_quantity, returned_quantity, replacement_required_quantity, return_verification_id
            """;

    private final JdbcTemplate jdbcTemplate;

    public AfterSalesRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insertCase(String caseNo, long orderId, String caseType, String problem, String solution,
                           String note, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO after_sales_cases (case_no, order_id, case_type, status, problem, solution, note,
                    version, created_at, updated_at, request_id)
                VALUES (?, ?, ?, 'OPEN', ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """, caseNo, orderId, caseType, problem, solution, note, requestId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM after_sales_cases WHERE case_no = ?", Long.class, caseNo);
    }

    public long insertItem(long caseId, long orderId, long orderItemId, long shipmentItemId, String productNo,
                           String productName, int seamQuantity, int acceptedQuantity, int returnedQuantity,
                           int replacementRequiredQuantity, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO after_sales_items (case_id, order_id, order_item_id, shipment_item_id, product_no,
                    product_name, seam_quantity, accepted_quantity, returned_quantity,
                    replacement_required_quantity, version, created_at, updated_at, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """, caseId, orderId, orderItemId, shipmentItemId, productNo, productName, seamQuantity,
                acceptedQuantity, returnedQuantity, replacementRequiredQuantity, requestId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM after_sales_items WHERE shipment_item_id = ?", Long.class, shipmentItemId);
    }

    public Optional<CaseRow> findCase(long caseId) {
        return jdbcTemplate.query("SELECT " + CASE_COLUMNS + " FROM after_sales_cases WHERE id = ?",
                this::mapCase, caseId).stream().findFirst();
    }

    public List<CaseRow> findCasesByOrder(long orderId) {
        return jdbcTemplate.query("SELECT " + CASE_COLUMNS
                + " FROM after_sales_cases WHERE order_id = ? ORDER BY id", this::mapCase, orderId);
    }

    public List<ItemRow> findItems(long caseId) {
        return jdbcTemplate.query("SELECT " + ITEM_COLUMNS
                + " FROM after_sales_items WHERE case_id = ? ORDER BY id", this::mapItem, caseId);
    }

    public Optional<ItemRow> findItem(long itemId) {
        return jdbcTemplate.query("SELECT " + ITEM_COLUMNS + " FROM after_sales_items WHERE id = ?",
                this::mapItem, itemId).stream().findFirst();
    }

    /** 该发货批次明细是否已被有效售后占用（唯一键的预检，用于返回清晰的 409）。 */
    public Optional<ItemRow> findItemByShipmentItem(long shipmentItemId) {
        return jdbcTemplate.query("SELECT " + ITEM_COLUMNS + " FROM after_sales_items WHERE shipment_item_id = ?",
                this::mapItem, shipmentItemId).stream().findFirst();
    }

    public Optional<ItemRow> findItemForUpdate(long itemId) {
        return jdbcTemplate.query("SELECT " + ITEM_COLUMNS + " FROM after_sales_items WHERE id = ? FOR UPDATE",
                this::mapItem, itemId).stream().findFirst();
    }

    /** 只读原发货批次明细（含批次状态），用于读模型展示；受理校验必须用 {@link #lockSource}。 */
    public Optional<SourceRow> findSource(long shipmentItemId) {
        return jdbcTemplate.query("""
                SELECT si.id, si.shipment_id, s.shipment_no, s.status, si.order_item_id, si.quantity,
                       si.product_no, si.product_name,
                       EXISTS (SELECT 1 FROM after_sales_shipment_links l WHERE l.shipment_id = s.id)
                           after_sales_replacement
                FROM shipment_items si JOIN shipments s ON s.id = si.shipment_id
                WHERE si.id = ?
                """, (rs, rowNum) -> new SourceRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getLong(5), rs.getInt(6), rs.getString(7), rs.getString(8), rs.getBoolean(9)),
                shipmentItemId).stream().findFirst();
    }

    /** 锁定读原发货批次明细（含批次状态），用于受理来源校验。 */
    public Optional<SourceRow> lockSource(long shipmentItemId) {
        return jdbcTemplate.query("""
                SELECT si.id, si.shipment_id, s.shipment_no, s.status, si.order_item_id, si.quantity,
                       si.product_no, si.product_name,
                       EXISTS (SELECT 1 FROM after_sales_shipment_links l WHERE l.shipment_id = s.id)
                           after_sales_replacement
                FROM shipment_items si JOIN shipments s ON s.id = si.shipment_id
                WHERE si.id = ? FOR UPDATE
                """, (rs, rowNum) -> new SourceRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getLong(5), rs.getInt(6), rs.getString(7), rs.getString(8), rs.getBoolean(9)),
                shipmentItemId).stream().findFirst();
    }

    public long insertReturnVerification(long afterSalesItemId, int returnedQuantity, int reworkQuantity,
                                         int scrapQuantity, String reason, String verifiedBy, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO after_sales_return_verifications (after_sales_item_id, returned_quantity,
                    rework_quantity, scrap_quantity, reason, verified_by, verified_at, created_at, updated_at,
                    request_id)
                VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """, afterSalesItemId, returnedQuantity, reworkQuantity, scrapQuantity, reason, verifiedBy,
                requestId);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM after_sales_return_verifications WHERE after_sales_item_id = ?
                """, Long.class, afterSalesItemId);
    }

    public Optional<ReturnVerificationRow> findReturnVerification(long afterSalesItemId) {
        return jdbcTemplate.query("""
                SELECT id, after_sales_item_id, returned_quantity, rework_quantity, scrap_quantity, reason,
                       verified_by
                FROM after_sales_return_verifications WHERE after_sales_item_id = ?
                """, (rs, rowNum) -> new ReturnVerificationRow(rs.getLong(1), rs.getLong(2), rs.getInt(3),
                rs.getInt(4), rs.getInt(5), rs.getString(6), rs.getString(7)), afterSalesItemId)
                .stream().findFirst();
    }

    public void markReturnVerified(long afterSalesItemId, long verificationId, int returnedQuantity,
                                   String requestId) {
        jdbcTemplate.update("""
                UPDATE after_sales_items SET return_verification_id = ?, returned_quantity = ?,
                    version = version + 1, updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ?
                """, verificationId, returnedQuantity, requestId, afterSalesItemId);
    }

    public long insertEntry(long afterSalesItemId, String entryType, String direction, int quantity,
                            String sourceType, long sourceId, long sourceLineId, LocalDate businessDate,
                            String operatorUsername, String note, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO after_sales_fulfillment_entries (after_sales_item_id, entry_type, direction,
                    quantity, source_type, source_id, source_line_id, business_date, operator_username, note,
                    version, created_at, updated_at, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """, afterSalesItemId, entryType, direction, quantity, sourceType, sourceId, sourceLineId,
                Date.valueOf(businessDate), operatorUsername, note, requestId);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM after_sales_fulfillment_entries WHERE after_sales_item_id = ? AND source_type = ?
                    AND source_id = ? AND source_line_id = ? AND entry_type = ? AND direction = ?
                """, Long.class, afterSalesItemId, sourceType, sourceId, sourceLineId, entryType, direction);
    }

    /**
     * 可补发 = 入库类事实 − 补发消耗类事实。
     * 读模型用普通读；写路径的余额校验必须用 {@link #lockAvailableQuantity}。
     */
    public int availableQuantity(long afterSalesItemId) {
        var value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'IN' THEN quantity ELSE -quantity END), 0)
                FROM after_sales_fulfillment_entries WHERE after_sales_item_id = ?
                """, Integer.class, afterSalesItemId);
        return value == null ? 0 : value;
    }

    /** 锁定读可补发余额：REPEATABLE READ 下普通读会用事务早期快照，读到并发前的旧余额。 */
    public int lockAvailableQuantity(long afterSalesItemId) {
        var value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'IN' THEN quantity ELSE -quantity END), 0)
                FROM after_sales_fulfillment_entries WHERE after_sales_item_id = ? FOR UPDATE
                """, Integer.class, afterSalesItemId);
        return value == null ? 0 : value;
    }

    /** 已补发 = 补发消耗事实合计；读模型用普通读。 */
    public int shippedQuantity(long afterSalesItemId) {
        var value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM after_sales_fulfillment_entries
                WHERE after_sales_item_id = ? AND entry_type = 'REPLACEMENT_CONSUME' AND direction = 'OUT'
                """, Integer.class, afterSalesItemId);
        return value == null ? 0 : value;
    }

    /** 锁定读已补发数量，写路径必须用此方法。 */
    public int lockShippedQuantity(long afterSalesItemId) {
        var value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM after_sales_fulfillment_entries
                WHERE after_sales_item_id = ? AND entry_type = 'REPLACEMENT_CONSUME' AND direction = 'OUT'
                FOR UPDATE
                """, Integer.class, afterSalesItemId);
        return value == null ? 0 : value;
    }

    public long insertShipmentLink(long afterSalesItemId, long shipmentId, long shipmentItemId, int quantity,
                                   String requestId) {
        jdbcTemplate.update("""
                INSERT INTO after_sales_shipment_links (after_sales_item_id, shipment_id, shipment_item_id,
                    quantity, version, created_at, updated_at, request_id)
                VALUES (?, ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """, afterSalesItemId, shipmentId, shipmentItemId, quantity, requestId);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM after_sales_shipment_links WHERE after_sales_item_id = ? AND shipment_item_id = ?
                """, Long.class, afterSalesItemId, shipmentItemId);
    }

    /** 按补发发货明细反查售后明细（补发批次的明细通过关联表指向售后明细，而不是 after_sales_items.shipment_item_id）。 */
    public Optional<Long> findLinkedAfterSalesItemId(long caseId, long replacementShipmentItemId) {
        return jdbcTemplate.query("""
                SELECT l.after_sales_item_id FROM after_sales_shipment_links l
                JOIN after_sales_items i ON i.id = l.after_sales_item_id
                WHERE l.shipment_item_id = ? AND i.case_id = ?
                """, (rs, rowNum) -> rs.getLong(1), replacementShipmentItemId, caseId).stream().findFirst();
    }

    /** 该售后单已创建的补发批次 id（页面据此确认补发）。 */
    public List<Long> findReplacementShipmentIds(long caseId) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT l.shipment_id FROM after_sales_shipment_links l
                JOIN after_sales_items i ON i.id = l.after_sales_item_id
                WHERE i.case_id = ? ORDER BY l.shipment_id
                """, Long.class, caseId);
    }

    public long insertCorrection(long caseId, String targetType, long targetId, String beforeValue,
                                 String afterValue, String reason, String operatorUsername, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO after_sales_corrections (case_id, target_type, target_id, before_value, after_value,
                    reason, operator_username, version, created_at, updated_at, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """, caseId, targetType, targetId, beforeValue, afterValue, reason, operatorUsername, requestId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM after_sales_corrections WHERE case_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, caseId);
    }

    public void markCaseCompleted(long caseId, String closedBy, String requestId) {
        jdbcTemplate.update("""
                UPDATE after_sales_cases SET status = 'COMPLETED', closed_at = UTC_TIMESTAMP(6), closed_by = ?,
                    version = version + 1, updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ?
                """, closedBy, requestId, caseId);
    }

    /** 更正记录行：原值/新值/原因必须回传（任务 8.9「保留原值与来源链」的读模型）。 */
    public record CorrectionRow(long id, long targetId, String beforeValue, String afterValue, String reason) {
    }

    public List<CorrectionRow> findCorrections(long caseId) {
        return jdbcTemplate.query("""
                SELECT id, target_id, before_value, after_value, reason FROM after_sales_corrections
                WHERE case_id = ? ORDER BY id
                """, (rs, rowNum) -> new CorrectionRow(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getString(4), rs.getString(5)), caseId);
    }

    /** 售后单的退款事实（阶段七表，按 source_type=AFTER_SALES + source_id=caseId 关联）。 */
    public List<java.util.Map<String, Object>> findRefunds(long caseId) {
        return jdbcTemplate.queryForList("""
                SELECT refund_no, amount, business_date, method, reason FROM refunds
                WHERE source_type = 'AFTER_SALES' AND source_id = ? ORDER BY business_date, id
                """, caseId);
    }

    private CaseRow mapCase(ResultSet rs, int rowNum) throws SQLException {
        return new CaseRow(rs.getLong("id"), rs.getString("case_no"), rs.getLong("order_id"),
                rs.getString("case_type"), rs.getString("status"), rs.getString("problem"),
                rs.getString("solution"), rs.getString("note"));
    }

    private ItemRow mapItem(ResultSet rs, int rowNum) throws SQLException {
        long verificationId = rs.getLong("return_verification_id");
        boolean verificationNull = rs.wasNull();
        return new ItemRow(rs.getLong("id"), rs.getLong("case_id"), rs.getLong("order_id"),
                rs.getLong("order_item_id"), rs.getLong("shipment_item_id"), rs.getString("product_no"),
                rs.getString("product_name"), rs.getInt("seam_quantity"), rs.getInt("accepted_quantity"),
                rs.getInt("returned_quantity"), rs.getInt("replacement_required_quantity"),
                verificationNull ? null : verificationId);
    }
}

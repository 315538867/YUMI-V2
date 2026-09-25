package com.yumi.orders.settlement.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * `payments` / `refunds` / `order_settlement_balances` 访问（阶段七）：显式列读写。
 * 收退款只追加，不提供更新/删除；投影由领域服务在写事实的同一事务内更新。
 */
@Repository
public class SettlementRepository {

    public record PaymentRow(long id, String paymentNo, long orderId, BigDecimal amount,
                             LocalDate businessDate, String method, String note, String operatorUsername) {
    }

    public record RefundRow(long id, String refundNo, long orderId, BigDecimal amount, LocalDate businessDate,
                            String method, String reason, String note, String sourceType, long sourceId,
                            String operatorUsername) {
    }

    public record SettlementRow(long orderId, BigDecimal paidAmount, BigDecimal changeRefundAmount,
                                BigDecimal afterSalesRefundAmount, BigDecimal netSettledAmount,
                                BigDecimal effectiveReceivableAmount, BigDecimal refundPendingAmount) {
    }

    private final JdbcTemplate jdbcTemplate;

    public SettlementRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insertPayment(String paymentNo, long orderId, BigDecimal amount, LocalDate businessDate,
                              String method, String note, String operatorUsername, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO payments (payment_no, order_id, amount, business_date, method, note,
                    operator_username, version, created_at, updated_at, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """, paymentNo, orderId, amount, Date.valueOf(businessDate), method, note, operatorUsername,
                requestId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM payments WHERE payment_no = ?", Long.class, paymentNo);
    }

    public long insertRefund(String refundNo, long orderId, BigDecimal amount, LocalDate businessDate,
                             String method, String reason, String note, String sourceType, long sourceId,
                             String operatorUsername, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO refunds (refund_no, order_id, amount, business_date, method, reason, note,
                    source_type, source_id, operator_username, version, created_at, updated_at, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """, refundNo, orderId, amount, Date.valueOf(businessDate), method, reason, note, sourceType,
                sourceId, operatorUsername, requestId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM refunds WHERE refund_no = ?", Long.class, refundNo);
    }

    public List<PaymentRow> findPayments(long orderId) {
        return jdbcTemplate.query("""
                SELECT id, payment_no, order_id, amount, business_date, method, note, operator_username
                FROM payments WHERE order_id = ? ORDER BY business_date, id
                """, (rs, rowNum) -> new PaymentRow(rs.getLong("id"), rs.getString("payment_no"),
                rs.getLong("order_id"), rs.getBigDecimal("amount"),
                rs.getDate("business_date").toLocalDate(), rs.getString("method"), rs.getString("note"),
                rs.getString("operator_username")), orderId);
    }

    public List<RefundRow> findRefunds(long orderId) {
        return jdbcTemplate.query("""
                SELECT id, refund_no, order_id, amount, business_date, method, reason, note, source_type,
                       source_id, operator_username
                FROM refunds WHERE order_id = ? ORDER BY business_date, id
                """, (rs, rowNum) -> new RefundRow(rs.getLong("id"), rs.getString("refund_no"),
                rs.getLong("order_id"), rs.getBigDecimal("amount"),
                rs.getDate("business_date").toLocalDate(), rs.getString("method"), rs.getString("reason"),
                rs.getString("note"), rs.getString("source_type"), rs.getLong("source_id"),
                rs.getString("operator_username")), orderId);
    }

    /** 累计收款（只追加事实的汇总）。 */
    public BigDecimal sumPayments(long orderId) {
        var value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM payments WHERE order_id = ?", BigDecimal.class, orderId);
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 累计退款（可按来源类型过滤）。 */
    public BigDecimal sumRefunds(long orderId, String sourceType) {
        var value = sourceType == null
                ? jdbcTemplate.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM refunds WHERE order_id = ?",
                        BigDecimal.class, orderId)
                : jdbcTemplate.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM refunds "
                        + "WHERE order_id = ? AND source_type = ?", BigDecimal.class, orderId, sourceType);
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 确保投影行存在（首次登记时创建），返回锁定读后的行。 */
    public SettlementRow lockOrCreate(long orderId, BigDecimal effectiveReceivable, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO order_settlement_balances (order_id, effective_receivable_amount, version,
                    created_at, updated_at, request_id)
                VALUES (?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                ON DUPLICATE KEY UPDATE order_id = order_id
                """, orderId, effectiveReceivable, requestId);
        return jdbcTemplate.query("""
                SELECT order_id, paid_amount, change_refund_amount, after_sales_refund_amount,
                       net_settled_amount, effective_receivable_amount, refund_pending_amount
                FROM order_settlement_balances WHERE order_id = ? FOR UPDATE
                """, this::mapSettlement, orderId).stream().findFirst().orElseThrow();
    }

    public Optional<SettlementRow> findSettlement(long orderId) {
        return jdbcTemplate.query("""
                SELECT order_id, paid_amount, change_refund_amount, after_sales_refund_amount,
                       net_settled_amount, effective_receivable_amount, refund_pending_amount
                FROM order_settlement_balances WHERE order_id = ?
                """, this::mapSettlement, orderId).stream().findFirst();
    }

    /** 投影 upsert：首次登记时插入，之后更新（投影是缓存，权威仍是不可变事实）。 */
    public void updateSettlement(SettlementRow row, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO order_settlement_balances (order_id, paid_amount, change_refund_amount,
                    after_sales_refund_amount, net_settled_amount, effective_receivable_amount,
                    refund_pending_amount, version, created_at, updated_at, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                ON DUPLICATE KEY UPDATE paid_amount = VALUES(paid_amount),
                    change_refund_amount = VALUES(change_refund_amount),
                    after_sales_refund_amount = VALUES(after_sales_refund_amount),
                    net_settled_amount = VALUES(net_settled_amount),
                    effective_receivable_amount = VALUES(effective_receivable_amount),
                    refund_pending_amount = VALUES(refund_pending_amount),
                    version = version + 1, updated_at = UTC_TIMESTAMP(6), request_id = VALUES(request_id)
                """, row.orderId(), row.paidAmount(), row.changeRefundAmount(), row.afterSalesRefundAmount(),
                row.netSettledAmount(), row.effectiveReceivableAmount(), row.refundPendingAmount(), requestId);
    }

    /** 每条明细的 (当前有效需求, 累计有效发货, 明细序号)：关闭前逐项校验有效需求是否全部有效发货。 */
    public List<int[]> findDeliveryGaps(long orderId) {
        return jdbcTemplate.query("""
                SELECT b.required_quantity, b.shipped_quantity, i.line_no
                FROM order_item_fulfillment_balances b JOIN order_items i ON i.id = b.order_item_id
                WHERE b.order_id = ? ORDER BY i.line_no
                """, (rs, rowNum) -> new int[]{rs.getInt(1), rs.getInt(2), rs.getInt(3)}, orderId);
    }

    private SettlementRow mapSettlement(ResultSet rs, int rowNum) throws SQLException {
        return new SettlementRow(rs.getLong("order_id"), rs.getBigDecimal("paid_amount"),
                rs.getBigDecimal("change_refund_amount"), rs.getBigDecimal("after_sales_refund_amount"),
                rs.getBigDecimal("net_settled_amount"), rs.getBigDecimal("effective_receivable_amount"),
                rs.getBigDecimal("refund_pending_amount"));
    }
}

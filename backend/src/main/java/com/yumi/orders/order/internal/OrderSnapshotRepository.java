package com.yumi.orders.order.internal;

import com.yumi.orders.order.OrderService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;

/**
 * 订单确认快照写入（任务 3.4）：订单级 + 明细级各一行，确认后不可修改。
 * 只由 {@link OrderService#confirm} 在同一事务内调用。
 */
@Repository
public class OrderSnapshotRepository {

    private final JdbcTemplate jdbcTemplate;

    public OrderSnapshotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertOrderSnapshot(OrderRow order, OrderReference.Customer customer, String confirmedBy,
                                    String requestId) {
        jdbcTemplate.update("""
                INSERT INTO order_confirmation_snapshots (order_id, customer_id, customer_no, customer_name,
                    contact, phone, recipient_name, recipient_phone, region, address, note,
                    goods_amount, seam_amount, discount_amount, receivable_amount,
                    goods_cost_amount, seam_cost_amount, cost_amount, profit_amount,
                    confirmed_by, confirmed_at, created_at, updated_at, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """,
                order.id(), customer.id(), customer.customerNo(), customer.name(), customer.contact(),
                customer.phone(), order.recipientName(), order.recipientPhone(), order.region(), order.address(),
                order.note(), order.goodsAmount(), order.seamAmount(), order.discountAmount(),
                order.receivableAmount(), order.goodsCostAmount(), order.seamCostAmount(), order.costAmount(),
                order.profitAmount(), confirmedBy, requestId);
    }

    public void insertItemSnapshot(OrderRow order, OrderItemRow item, OrderReference.Product product,
                                   String flow, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO order_item_snapshots (order_id, order_item_id, line_no, product_id, product_no,
                    product_name, product_note, image_file_id, star_level_id, star_name, star_std_minutes,
                    quantity, seam_quantity, unit_price, goods_amount, seam_type_id, seam_type_name,
                    seam_unit_cost, seam_fee, seam_amount, unit_cost, goods_cost_amount, seam_cost_amount,
                    glue_grams, glue_cost, colorpaste_cost, material_cost, product_labor_fee,
                    packaging_labor_fee, box_labor_fee, labor_cost, other_cost, total_cost, flow, note,
                    created_at, updated_at, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """,
                order.id(), item.id(), item.lineNo(), item.productId(), product.productNo(), product.name(),
                product.note(), product.imageFileId(), product.starLevelId(), product.starName(),
                product.starStdMinutes(), item.quantity(), item.seamQuantity(), item.unitPrice(),
                item.goodsAmount(), item.seamTypeId(), item.seamTypeName(), item.seamUnitCost(), item.seamFee(),
                item.seamAmount(), item.unitCost(), item.goodsCostAmount(), item.seamCostAmount(),
                product.glueGrams(), product.glueCost(), product.colorpasteCost(), product.materialCost(),
                product.productLaborFee(), product.packagingLaborFee(), product.boxLaborFee(),
                product.laborCost(), product.otherCost(), product.totalCost(), flow, item.note(), requestId);
    }

    public Integer orderSnapshotCount(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_confirmation_snapshots WHERE order_id = ?", Integer.class, orderId);
    }

    public Integer itemSnapshotCount(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_item_snapshots WHERE order_id = ?", Integer.class, orderId);
    }

    public java.util.Map<String, Object> findOrderSnapshot(long orderId) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM order_confirmation_snapshots WHERE order_id = ?", orderId);
    }

    public java.util.List<java.util.Map<String, Object>> findItemSnapshots(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM order_item_snapshots WHERE order_id = ? ORDER BY line_no", orderId);
    }

    /** 订单确认状态与确认信息写入（带乐观版本条件）。 */
    public int markConfirmed(long orderId, long expectedVersion, String confirmedBy, String requestId) {
        return jdbcTemplate.update("""
                UPDATE orders SET status = 'CONFIRMED', confirmed_at = UTC_TIMESTAMP(6), confirmed_by = ?,
                    version = version + 1, updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ? AND version = ?
                """, confirmedBy, requestId, orderId, expectedVersion);
    }

    /** 关闭订单：置主状态并记录关闭人与时间（已关闭不得重开，由服务层保证）。 */
    public int markClosed(long orderId, String closedBy, String requestId) {
        return jdbcTemplate.update("""
                UPDATE orders SET status = 'CLOSED', closed_at = UTC_TIMESTAMP(6), closed_by = ?,
                    version = version + 1, updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ? AND status = 'CONFIRMED'
                """, closedBy, requestId, orderId);
    }

    public void markCancelled(long orderId, String cancelledBy, String reason, String requestId) {
        jdbcTemplate.update("""
                UPDATE orders SET status = 'CANCELLED', cancelled_at = UTC_TIMESTAMP(6), cancelled_by = ?,
                    cancel_reason = ?, version = version + 1, updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ?
                """, cancelledBy, reason, requestId, orderId);
    }

    /** 变更确认后回写订单表头（交期/收货/备注/优惠与重算金额），带乐观版本条件。 */
    public int applyChangeHeader(OrderRow order, long expectedVersion, String requestId) {
        return jdbcTemplate.update("""
                UPDATE orders SET expected_delivery_date = ?, recipient_name = ?, recipient_phone = ?,
                    region = ?, address = ?, note = ?, goods_amount = ?, seam_amount = ?,
                    discount_amount = ?, receivable_amount = ?, goods_cost_amount = ?, seam_cost_amount = ?,
                    cost_amount = ?, profit_amount = ?, version = version + 1,
                    updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ? AND version = ?
                """,
                order.expectedDeliveryDate() == null ? null : Date.valueOf(order.expectedDeliveryDate()),
                order.recipientName(), order.recipientPhone(), order.region(), order.address(), order.note(),
                order.goodsAmount(), order.seamAmount(), order.discountAmount(), order.receivableAmount(),
                order.goodsCostAmount(), order.seamCostAmount(), order.costAmount(), order.profitAmount(),
                requestId, order.id(), expectedVersion);
    }

    public void deleteItemSnapshots(long orderId) {
        jdbcTemplate.update("DELETE FROM order_item_snapshots WHERE order_id = ?", orderId);
    }
}

package com.yumi.orders.order.internal;

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
 * orders / order_items 访问：显式列读写（沿用库内 JdbcTemplate 风格）。
 * 订单更新带乐观版本条件，失配映射为 CONFLICT_VERSION；草稿明细整体替换（已确认后由变更单维护）。
 */
@Repository
public class OrderRepository {

    private static final String ORDER_COLUMNS = """
            id, order_no, customer_id, customer_name, status, order_date, expected_delivery_date,
            recipient_name, recipient_phone, region, address, note,
            goods_amount, seam_amount, discount_amount, receivable_amount,
            goods_cost_amount, seam_cost_amount, cost_amount, profit_amount, version
            """;

    private static final String ITEM_COLUMNS = """
            id, order_id, line_no, product_id, product_no, product_name, quantity, seam_quantity,
            unit_price, goods_amount, seam_type_id, seam_type_name, seam_unit_cost, seam_fee, seam_amount,
            unit_cost, goods_cost_amount, seam_cost_amount, note, version
            """;

    private static final RowMapper<OrderRow> ORDER_MAPPER = OrderRepository::mapOrder;
    private static final RowMapper<OrderItemRow> ITEM_MAPPER = OrderRepository::mapItem;
    private static final RowMapper<OrderPlanLineRow> PLAN_MAPPER = OrderRepository::mapPlanLine;

    private final JdbcTemplate jdbcTemplate;

    public OrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<OrderRow> findById(long id) {
        var rows = jdbcTemplate.query("SELECT " + ORDER_COLUMNS + " FROM orders WHERE id = ?", ORDER_MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 列表筛选：状态、客户、下单日期区间均可选。 */
    public List<OrderRow> find(String status, Long customerId, LocalDate from, LocalDate to) {
        var sql = new StringBuilder("SELECT " + ORDER_COLUMNS + " FROM orders WHERE 1=1");
        var args = new ArrayList<Object>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim());
        }
        if (customerId != null) {
            sql.append(" AND customer_id = ?");
            args.add(customerId);
        }
        if (from != null) {
            sql.append(" AND order_date >= ?");
            args.add(Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND order_date <= ?");
            args.add(Date.valueOf(to));
        }
        sql.append(" ORDER BY id DESC");
        return jdbcTemplate.query(sql.toString(), ORDER_MAPPER, args.toArray());
    }

    public long insertOrder(OrderRow row, String requestId, String idempotencyKey) {
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, customer_id, customer_name, status, order_date,
                    expected_delivery_date, recipient_name, recipient_phone, region, address, note,
                    goods_amount, seam_amount, discount_amount, receivable_amount,
                    goods_cost_amount, seam_cost_amount, cost_amount, profit_amount,
                    version, created_at, updated_at, request_id, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0,
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, ?)
                """,
                row.orderNo(), row.customerId(), row.customerName(), row.status(),
                Date.valueOf(row.orderDate()),
                row.expectedDeliveryDate() == null ? null : Date.valueOf(row.expectedDeliveryDate()),
                row.recipientName(), row.recipientPhone(), row.region(), row.address(), row.note(),
                row.goodsAmount(), row.seamAmount(), row.discountAmount(), row.receivableAmount(),
                row.goodsCostAmount(), row.seamCostAmount(), row.costAmount(), row.profitAmount(),
                requestId, idempotencyKey);
        return jdbcTemplate.queryForObject("SELECT id FROM orders WHERE order_no = ?", Long.class, row.orderNo());
    }

    /** 按旧版本整体覆盖订单业务列并递增版本；版本失配 → CONFLICT_VERSION。 */
    public void updateOrder(OrderRow row, String requestId) {
        int updated = jdbcTemplate.update("""
                UPDATE orders SET
                    customer_id = ?, customer_name = ?, order_date = ?, expected_delivery_date = ?,
                    recipient_name = ?, recipient_phone = ?, region = ?, address = ?, note = ?,
                    goods_amount = ?, seam_amount = ?, discount_amount = ?, receivable_amount = ?,
                    goods_cost_amount = ?, seam_cost_amount = ?, cost_amount = ?, profit_amount = ?,
                    version = version + 1, updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ? AND version = ?
                """,
                row.customerId(), row.customerName(), Date.valueOf(row.orderDate()),
                row.expectedDeliveryDate() == null ? null : Date.valueOf(row.expectedDeliveryDate()),
                row.recipientName(), row.recipientPhone(), row.region(), row.address(), row.note(),
                row.goodsAmount(), row.seamAmount(), row.discountAmount(), row.receivableAmount(),
                row.goodsCostAmount(), row.seamCostAmount(), row.costAmount(), row.profitAmount(),
                requestId, row.id(), row.version());
        if (updated == 0) {
            throw new ApiException(ErrorCode.CONFLICT_VERSION);
        }
    }

    public List<OrderItemRow> findItems(long orderId) {
        return jdbcTemplate.query(
                "SELECT " + ITEM_COLUMNS + " FROM order_items WHERE order_id = ? ORDER BY line_no",
                ITEM_MAPPER, orderId);
    }

    public long findItemId(long orderId, int lineNo) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND line_no = ?", Long.class, orderId, lineNo);
    }

    public void deleteItems(long orderId) {
        jdbcTemplate.update("DELETE FROM order_items WHERE order_id = ?", orderId);
    }

    // ---------- 草稿库存计划（任务 4.8） ----------

    /** 计划行按明细序号排序，便于与明细表逐行对照。 */
    public List<OrderPlanLineRow> findPlanLines(long orderId) {
        return jdbcTemplate.query("""
                SELECT p.id, p.order_id, p.order_item_id, i.line_no, p.batch_id, p.quantity
                FROM order_inventory_plan_lines p
                JOIN order_items i ON i.id = p.order_item_id
                WHERE p.order_id = ?
                ORDER BY i.line_no, p.id
                """, PLAN_MAPPER, orderId);
    }

    /** 计划行对明细有外键，整体替换明细前必须先调用本方法，否则删明细会被外键挡住。 */
    public void deletePlanLines(long orderId) {
        jdbcTemplate.update("DELETE FROM order_inventory_plan_lines WHERE order_id = ?", orderId);
    }

    public void insertPlanLine(long orderId, long orderItemId, long batchId, int quantity, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO order_inventory_plan_lines (order_id, order_item_id, batch_id, quantity,
                    version, created_at, updated_at, request_id)
                VALUES (?, ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """, orderId, orderItemId, batchId, quantity, requestId);
    }

    /** 变更确认后按 id 覆盖明细业务列（并发守卫是订单级 {@code version}）。 */
    public void updateItem(OrderItemRow row, String requestId) {
        jdbcTemplate.update("""
                UPDATE order_items SET line_no = ?, product_id = ?, product_no = ?, product_name = ?,
                    quantity = ?, seam_quantity = ?, unit_price = ?, goods_amount = ?,
                    seam_type_id = ?, seam_type_name = ?, seam_unit_cost = ?, seam_fee = ?, seam_amount = ?,
                    unit_cost = ?, goods_cost_amount = ?, seam_cost_amount = ?, note = ?,
                    version = version + 1, updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ?
                """,
                row.lineNo(), row.productId(), row.productNo(), row.productName(), row.quantity(),
                row.seamQuantity(), row.unitPrice(), row.goodsAmount(), row.seamTypeId(), row.seamTypeName(),
                row.seamUnitCost(), row.seamFee(), row.seamAmount(), row.unitCost(), row.goodsCostAmount(),
                row.seamCostAmount(), row.note(), requestId, row.id());
    }

    public void insertItem(OrderItemRow row, String requestId) {        jdbcTemplate.update("""
                INSERT INTO order_items (order_id, line_no, product_id, product_no, product_name,
                    quantity, seam_quantity, unit_price, goods_amount,
                    seam_type_id, seam_type_name, seam_unit_cost, seam_fee, seam_amount,
                    unit_cost, goods_cost_amount, seam_cost_amount, note,
                    version, created_at, updated_at, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0,
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """,
                row.orderId(), row.lineNo(), row.productId(), row.productNo(), row.productName(),
                row.quantity(), row.seamQuantity(), row.unitPrice(), row.goodsAmount(),
                row.seamTypeId(), row.seamTypeName(), row.seamUnitCost(), row.seamFee(), row.seamAmount(),
                row.unitCost(), row.goodsCostAmount(), row.seamCostAmount(), row.note(), requestId);
    }

    private static OrderRow mapOrder(ResultSet rs, int rowNum) throws SQLException {
        return new OrderRow(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getLong("customer_id"),
                rs.getString("customer_name"),
                rs.getString("status"),
                toLocalDate(rs.getDate("order_date")),
                toLocalDate(rs.getDate("expected_delivery_date")),
                rs.getString("recipient_name"),
                rs.getString("recipient_phone"),
                rs.getString("region"),
                rs.getString("address"),
                rs.getString("note"),
                rs.getBigDecimal("goods_amount"),
                rs.getBigDecimal("seam_amount"),
                rs.getBigDecimal("discount_amount"),
                rs.getBigDecimal("receivable_amount"),
                rs.getBigDecimal("goods_cost_amount"),
                rs.getBigDecimal("seam_cost_amount"),
                rs.getBigDecimal("cost_amount"),
                rs.getBigDecimal("profit_amount"),
                rs.getLong("version"));
    }

    private static OrderItemRow mapItem(ResultSet rs, int rowNum) throws SQLException {
        long seamTypeId = rs.getLong("seam_type_id");
        boolean seamTypeNull = rs.wasNull();
        return new OrderItemRow(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getInt("line_no"),
                rs.getLong("product_id"),
                rs.getString("product_no"),
                rs.getString("product_name"),
                rs.getInt("quantity"),
                rs.getInt("seam_quantity"),
                rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("goods_amount"),
                seamTypeNull ? null : seamTypeId,
                rs.getString("seam_type_name"),
                rs.getBigDecimal("seam_unit_cost"),
                rs.getBigDecimal("seam_fee"),
                rs.getBigDecimal("seam_amount"),
                rs.getBigDecimal("unit_cost"),
                rs.getBigDecimal("goods_cost_amount"),
                rs.getBigDecimal("seam_cost_amount"),
                rs.getString("note"),
                rs.getLong("version"));
    }

    private static LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }

    private static OrderPlanLineRow mapPlanLine(ResultSet rs, int rowNum) throws SQLException {
        return new OrderPlanLineRow(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getLong("order_item_id"),
                rs.getInt("line_no"),
                rs.getLong("batch_id"),
                rs.getInt("quantity"));
    }
}

package com.yumi.inventory.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 库存参考数据读取：商品识别与状态、订单明细的追溯信息（订单号 + 明细序号）。
 * 与 `orders` 模块的 OrderReference 同风格：只读查询展示字段，不复制业务规则、不访问实体或 Repository。
 */
@Component
public class InventoryReference {

    public record Product(long id, String productNo, String name, String status) {
    }

    public record OrderItemRef(long orderItemId, long orderId, String orderNo, int lineNo) {
    }

    public record OrderRef(long orderId, String orderNo, String status) {
    }

    private final JdbcTemplate jdbcTemplate;

    public InventoryReference(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Product> product(long id) {
        var product = jdbcTemplate.query(
                "SELECT id, product_no, name, status FROM products WHERE id = ?",
                rs -> rs.next() ? new Product(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))
                        : null,
                id);
        return Optional.ofNullable(product);
    }

    public Optional<OrderRef> order(long orderId) {
        var ref = jdbcTemplate.query(
                "SELECT id, order_no, status FROM orders WHERE id = ?",
                rs -> rs.next() ? new OrderRef(rs.getLong(1), rs.getString(2), rs.getString(3)) : null,
                orderId);
        return Optional.ofNullable(ref);
    }

    /** 售后明细的只读上下文（任务 8.6）：取原订单明细上的商品，用于售后领用的商品兼容性校验。 */
    public record AfterSalesItemRef(long afterSalesItemId, long orderId, long productId, String productNo,
                                    String productName) {
    }

    /** 售后明细（JOIN 原订单明细取商品识别信息）。 */
    public Optional<AfterSalesItemRef> afterSalesItem(long afterSalesItemId) {
        return jdbcTemplate.query("""
                SELECT a.id, a.order_id, i.product_id, i.product_no, i.product_name
                FROM after_sales_items a JOIN order_items i ON i.id = a.order_item_id
                WHERE a.id = ?
                """, rs -> rs.next()
                ? Optional.of(new AfterSalesItemRef(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        rs.getString(4), rs.getString(5)))
                : Optional.empty(), afterSalesItemId);
    }

    public Optional<OrderItemRef> orderItem(long orderItemId) {
        var ref = jdbcTemplate.query("""
                SELECT i.id, o.id AS order_id, o.order_no, i.line_no
                FROM order_items i JOIN orders o ON o.id = i.order_id WHERE i.id = ?
                """, rs -> rs.next()
                ? new OrderItemRef(rs.getLong("id"), rs.getLong("order_id"), rs.getString("order_no"),
                        rs.getInt("line_no"))
                : null, orderItemId);
        return Optional.ofNullable(ref);
    }
}

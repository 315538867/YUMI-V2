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

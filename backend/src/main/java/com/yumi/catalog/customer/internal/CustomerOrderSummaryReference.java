package com.yumi.catalog.customer.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 客户汇总对订单侧的只读引用（任务 2.6 的「阶段 3/7 接线」）：纯 SQL 读取订单与收退款事实，
 * **不依赖 orders 模块的 Java 类型**（orders 已依赖 catalog，用 Java 类型会形成模块循环；
 * 与 `production/internal/OrderProductionReference`、`orders/order/internal/InventoryPlanReference` 同风格）。
 *
 * 口径：
 * <pre>
 * orderCount    = 该客户的**非草稿**订单数（草稿是内部工作态，不计入客户台账）
 * totalOrdered  = 上述订单的当前有效应收合计
 * totalReceived = 上述订单的收款事实合计
 * totalRefunded = 上述订单的退款事实合计（含订单变更退款与售后退款）
 * </pre>
 * 不落任何客户余额列；每次查询实时聚合。
 */
@Component
public class CustomerOrderSummaryReference {

    public record Summary(int orderCount, BigDecimal totalOrdered, BigDecimal totalReceived,
                          BigDecimal totalRefunded) {
    }

    private static final String SQL = """
            SELECT (SELECT COUNT(*) FROM orders o
                     WHERE o.customer_id = ? AND o.status <> 'DRAFT') order_count,
                   COALESCE((SELECT SUM(o.receivable_amount) FROM orders o
                     WHERE o.customer_id = ? AND o.status <> 'DRAFT'), 0) total_ordered,
                   COALESCE((SELECT SUM(p.amount) FROM payments p
                     JOIN orders o ON o.id = p.order_id
                     WHERE o.customer_id = ? AND o.status <> 'DRAFT'), 0) total_received,
                   COALESCE((SELECT SUM(r.amount) FROM refunds r
                     JOIN orders o ON o.id = r.order_id
                     WHERE o.customer_id = ? AND o.status <> 'DRAFT'), 0) total_refunded
            """;

    private final JdbcTemplate jdbcTemplate;

    public CustomerOrderSummaryReference(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Summary summary(long customerId) {
        return jdbcTemplate.queryForObject(SQL, (rs, rowNum) -> new Summary(rs.getInt(1),
                rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4)),
                customerId, customerId, customerId, customerId);
    }
}

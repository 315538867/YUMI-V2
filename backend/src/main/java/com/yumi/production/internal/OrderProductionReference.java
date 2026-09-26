package com.yumi.production.internal;

import com.yumi.production.ProductionNodes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 生产模块对订单侧的只读引用（阶段五）：读取订购数量、确认时冻结的缝边数量、履约投影的工序流入与累计发货。
 * 与 `orders/order/internal/InventoryPlanReference` 同风格——跨模块只读字段，**不依赖 orders 模块的 Java 类型**，
 * 也不复制订单业务规则（否则会与「生产向订单履约台账登记事实」形成模块循环）。
 */
@Component
public class OrderProductionReference {

    /**
     * 明细生产上下文：订购数量取当前有效值，缝边数量取确认时冻结值，
     * 三个 inflow 来自订单履约投影（由库存接入与生产核验共同推进）；
     * {@code inventoryBeyondMaking} 是库存直接接入到制作之后工序的数量，用于扣除首道制作的已满足部分。
     */
    public record OrderItemContext(long orderItemId, long orderId, String orderNo, String status,
                                   int lineNo, long productId, String productNo, String productName,
                                   int quantity, int seamQuantity,
                                   int makingInflow, int packingInflow, int seamInflow,
                                   int inventoryBeyondMaking, int shippedQuantity) {

        /** 该工序总需求：缝边剪袋按冻结的缝边数量，制作与捏毛装袋按订购数量。 */
        public int demand(String node) {
            return ProductionNodes.usesSeamQuantity(node) ? seamQuantity : quantity;
        }

        /** 该工序已登记的上游合格/库存流入。 */
        public int inflow(String node) {
            return switch (node) {
                case ProductionNodes.MAKING -> makingInflow;
                case ProductionNodes.PACKING_BAG -> packingInflow;
                case ProductionNodes.SEAM_CUTTING -> seamInflow;
                default -> 0;
            };
        }

        /**
         * 该工序的**有效流入**（当前可执行的基数）：首道制作没有上游工序，
         * 其流入来自订单实际制作缺口（订购数量扣除库存已直接满足、跳过制作的部分）。
         */
        public int effectiveInflow(String node) {
            if (!ProductionNodes.MAKING.equals(node)) {
                return inflow(node);
            }
            return Math.max(0, quantity - inventoryBeyondMaking);
        }
    }

    private static final String SQL = """
            SELECT i.id, i.order_id, o.order_no, o.status, i.line_no, i.product_id, i.product_no,
                   i.product_name, i.quantity, i.seam_quantity,
                   COALESCE(b.making_inflow, 0) making_inflow,
                   COALESCE(b.packing_inflow, 0) packing_inflow,
                   COALESCE(b.seam_inflow, 0) seam_inflow,
                   COALESCE(b.shipped_quantity, 0) shipped_quantity,
                   COALESCE((SELECT SUM(e.quantity) FROM fulfillment_entries e
                             WHERE e.order_item_id = i.id AND e.direction = 'IN'
                               AND e.entry_type = 'INVENTORY_ALLOCATION'
                               AND e.node IN ('PACKING_BAG', 'SEAM_CUTTING', 'SHIPPABLE')), 0)
                       inventory_beyond_making
            FROM order_items i
            JOIN orders o ON o.id = i.order_id
            LEFT JOIN order_item_fulfillment_balances b ON b.order_item_id = i.id
            """;

    private final JdbcTemplate jdbcTemplate;

    public OrderProductionReference(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public OrderItemContext item(long orderItemId) {
        return jdbcTemplate.query(SQL + " WHERE i.id = ?", rs -> rs.next() ? map(rs) : null, orderItemId);
    }

    /** 批量读取；明细不存在时该 id 不出现在结果里。 */
    public Map<Long, OrderItemContext> items(Collection<Long> orderItemIds) {
        var found = new LinkedHashMap<Long, OrderItemContext>();
        if (orderItemIds.isEmpty()) {
            return found;
        }
        var placeholders = String.join(",", Collections.nCopies(orderItemIds.size(), "?"));
        jdbcTemplate.query(SQL + " WHERE i.id IN (" + placeholders + ") ORDER BY i.id", rs -> {
            var item = map(rs);
            found.put(item.orderItemId(), item);
        }, orderItemIds.toArray());
        return found;
    }

    private static OrderItemContext map(ResultSet rs) throws SQLException {
        return new OrderItemContext(rs.getLong("id"), rs.getLong("order_id"), rs.getString("order_no"),
                rs.getString("status"), rs.getInt("line_no"), rs.getLong("product_id"), rs.getString("product_no"),
                rs.getString("product_name"), rs.getInt("quantity"), rs.getInt("seam_quantity"),
                rs.getInt("making_inflow"), rs.getInt("packing_inflow"), rs.getInt("seam_inflow"),
                rs.getInt("inventory_beyond_making"), rs.getInt("shipped_quantity"));
    }
}

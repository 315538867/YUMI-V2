package com.yumi.orders.api;

import java.time.Instant;
import java.util.List;

/**
 * 订单变化数据源：报表按稳定的 {@code (updatedAt, orderBusinessId)} 键集读取。
 *
 * <p>游标由本模块解释；重复读取或期间更新由消费者按订单标识去重取较新版本。
 */
public interface OrderReportingFeed {

    OrderChangePage changedSince(OrderChangeCursor cursor, int limit);

    record OrderChangeCursor(Instant updatedAt, String orderBusinessId) {
        public OrderChangeCursor {
            if (updatedAt == null || orderBusinessId == null || orderBusinessId.isBlank()) {
                throw new IllegalArgumentException("订单变化游标必须包含更新时间与订单标识");
            }
        }
    }

    record OrderChangePage(List<OrderChangeItem> items, OrderChangeCursor nextCursor) {
        public OrderChangePage {
            items = List.copyOf(items);
        }
    }

    record OrderChangeItem(
            String orderBusinessId, String orderNo, String origin, String status,
            String customerBusinessId, String customerSnapshotRef,
            java.time.LocalDate orderDate, java.time.LocalDate expectedDeliveryDate,
            java.math.BigDecimal currentReceivable, boolean deliveryFulfilled,
            boolean paymentFulfilled, long contentVersion, Instant updatedAt) {
    }
}

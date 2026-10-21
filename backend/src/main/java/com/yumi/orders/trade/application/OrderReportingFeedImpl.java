package com.yumi.orders.trade.application;

import com.yumi.orders.api.OrderReportingFeed;
import com.yumi.orders.application.port.OrderRepository;
import java.time.Instant;
import java.util.List;

/**
 * 订单变化数据源实现：按 {@code (updated_at, order_business_id)} 键集稳定分页。
 */
public class OrderReportingFeedImpl implements OrderReportingFeed {

    private final OrderRepository orders;

    public OrderReportingFeedImpl(OrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public OrderChangePage changedSince(OrderChangeCursor cursor, int limit) {
        if (limit <= 0 || limit > 500) {
            throw new IllegalArgumentException("订单变化分页大小必须在 1 到 500 之间");
        }
        Instant after = cursor == null ? Instant.EPOCH : cursor.updatedAt();
        String afterId = cursor == null ? "" : cursor.orderBusinessId();
        List<OrderRepository.OrderRecord> records =
                orders.findOrdersChangedAfter(after, afterId, limit);
        List<OrderChangeItem> items = records.stream()
                .map(record -> new OrderChangeItem(
                        record.orderBusinessId(), record.orderNo(), record.origin(), record.status(),
                        record.customerBusinessId(), record.customerSnapshotRef(),
                        record.orderDate(), record.expectedDeliveryDate(),
                        record.currentReceivableAmount(), record.deliveryFulfilled(),
                        record.paymentFulfilled(), record.contentVersion(), record.updatedAt()))
                .toList();
        OrderChangeCursor next = records.size() < limit
                ? null
                : new OrderChangeCursor(
                        records.getLast().updatedAt(), records.getLast().orderBusinessId());
        return new OrderChangePage(items, next);
    }
}

package com.yumi.orders.fulfillment;

import com.yumi.orders.fulfillment.internal.FulfillmentRepository;
import com.yumi.orders.order.internal.OrderItemRow;
import com.yumi.orders.order.internal.OrderRepository;
import com.yumi.orders.order.internal.OrderRow;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 履约读模型服务（任务 3.6/3.10）：共同数量按工序分流展示（不按工序相加），
 * 五类派生状态由事实与数量计算。阶段三工序流入/可发货/累计发货恒为 0，
 * 此时派生状态按事实判定为「未排产/等待上游/未开始/仍有待履约/未发货」，不用 0 伪造进度。
 */
@Service
public class FulfillmentService {

    private final OrderRepository orderRepository;
    private final FulfillmentRepository fulfillmentRepository;

    public FulfillmentService(OrderRepository orderRepository, FulfillmentRepository fulfillmentRepository) {
        this.orderRepository = orderRepository;
        this.fulfillmentRepository = fulfillmentRepository;
    }

    public FulfillmentViews.FulfillmentView view(long orderId) {
        var order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        return viewFor(order);
    }

    public FulfillmentViews.FulfillmentView viewFor(OrderRow order) {
        var balances = fulfillmentRepository.findBalances(order.id()).stream()
                .collect(Collectors.toMap(FulfillmentRepository.BalanceRow::orderItemId, Function.identity()));
        var items = orderRepository.findItems(order.id()).stream()
                .map(item -> toItem(item, balances.get(item.id())))
                .toList();
        return new FulfillmentViews.FulfillmentView(order.id(), order.orderNo(), order.status(),
                derivedOf(items), items);
    }

    /** 订单级派生状态：详情/列表复用同一口径。 */
    public OrderStatuses.Derived derivedFor(OrderRow order) {
        return viewFor(order).derived();
    }

    /** 订单级派生：明细数量求和后套用同一规则，保证与明细口径一致。 */
    public OrderStatuses.Derived derivedOf(List<FulfillmentViews.ItemFulfillment> items) {
        return OrderStatuses.derive(new OrderStatuses.Quantities(
                items.stream().mapToInt(FulfillmentViews.ItemFulfillment::quantity).sum(),
                items.stream().mapToInt(FulfillmentViews.ItemFulfillment::shipped).sum(),
                items.stream().mapToInt(FulfillmentViews.ItemFulfillment::makingInflow).sum(),
                items.stream().mapToInt(FulfillmentViews.ItemFulfillment::packingInflow).sum(),
                items.stream().mapToInt(FulfillmentViews.ItemFulfillment::seamInflow).sum(),
                items.stream().mapToInt(FulfillmentViews.ItemFulfillment::makingPlanned).sum(),
                items.stream().mapToInt(FulfillmentViews.ItemFulfillment::packingPlanned).sum(),
                items.stream().mapToInt(FulfillmentViews.ItemFulfillment::seamPlanned).sum(),
                items.stream().mapToInt(FulfillmentViews.ItemFulfillment::verifiedProcessed).sum(),
                items.stream().mapToInt(FulfillmentViews.ItemFulfillment::finishedSurplus).sum()));
    }

    /** 列表页派生状态：按订单一次汇总查询，避免逐单 N+1。 */
    public Map<Long, OrderStatuses.Derived> derivedByOrder() {
        return fulfillmentRepository.summarizeByOrder().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> OrderStatuses.derive(entry.getValue())));
    }

    private static FulfillmentViews.ItemFulfillment toItem(OrderItemRow item,
                                                          FulfillmentRepository.BalanceRow balance) {
        int makingInflow = balance == null ? 0 : balance.makingInflow();
        int packingInflow = balance == null ? 0 : balance.packingInflow();
        int seamInflow = balance == null ? 0 : balance.seamInflow();
        int makingPlanned = balance == null ? 0 : balance.makingPlanned();
        int packingPlanned = balance == null ? 0 : balance.packingPlanned();
        int seamPlanned = balance == null ? 0 : balance.seamPlanned();
        int verified = balance == null ? 0 : balance.verifiedProcessed();
        int shipped = balance == null ? 0 : balance.shippedQuantity();
        var derived = OrderStatuses.derive(new OrderStatuses.Quantities(item.quantity(), shipped,
                makingInflow, packingInflow, seamInflow, makingPlanned, packingPlanned, seamPlanned,
                verified, balance == null ? 0 : balance.finishedSurplusQuantity()));
        return new FulfillmentViews.ItemFulfillment(
                item.id(), item.lineNo(), item.productNo(), item.productName(),
                item.quantity(), item.seamQuantity(), item.quantity() - item.seamQuantity(),
                item.quantity(), item.quantity(), item.seamQuantity(), item.quantity(),
                makingInflow, packingInflow, seamInflow,
                makingPlanned, packingPlanned, seamPlanned, verified,
                balance == null ? 0 : balance.reworkPending(), balance == null ? 0 : balance.remakePending(),
                balance == null ? 0 : balance.shippableQuantity(), shipped,
                balance == null ? 0 : balance.finishedSurplusQuantity(),
                item.quantity() - shipped, derived);
    }
}

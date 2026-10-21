package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.yumi.finance.cash.api.CashApplicationService;
import com.yumi.finance.cash.api.CashQuery;
import com.yumi.finance.cash.api.ReceiveOrderPaymentCommand;
import com.yumi.orders.api.OrderQuery;
import com.yumi.orders.api.OrderRef;
import com.yumi.orders.api.OrderStatus;
import com.yumi.production.api.OrderProductionTrace;
import com.yumi.production.api.ProductionQuery;
import com.yumi.support.AbstractOrderFixtureTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 一期主业务链路：客户与商品 → 订单 → 排班核验 → 交付 → 收款 → 完成。
 *
 * <p>规则来源：`docs/一期/15-一期整体验收标准.md`。每个数量与金额都从权威事实或公开查询取得，
 * 不依赖报表投影；报表只做聚合展示。
 */
class PhaseOneBusinessFlowIT extends AbstractOrderFixtureTest {

    @Autowired
    private OrderQuery ordersQuery;

    @Autowired
    private ProductionQuery productionQuery;

    @Autowired
    private CashQuery cashQuery;

    @Autowired
    private CashApplicationService cash;

    @Test
    @DisplayName("客户、商品、订单、生产、交付、收款与完成全链可追溯")
    void phaseOneBusinessChainIsTraceable() {
        OrderRef order = confirmOrderForMaking(2);
        String itemId = orderJdbc.sql("""
                        SELECT order_item_business_id FROM orders_items
                        WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(String.class).single();
        String groupId = orderGroupId(order);
        String sourceId = orderSourceId(order);
        assertThat(countDemandsOfOrder(order.orderBusinessId())).isEqualTo(1);

        produceAtMaking(sourceId, 2);
        OrderProductionTrace trace = asAdmin(() -> productionQuery.traceOfOrderItem(itemId));
        assertThat(trace.orderBusinessId()).isEqualTo(order.orderBusinessId());
        assertThat(trace.demands()).hasSize(1);
        OrderProductionTrace.DemandTrace demand = trace.demands().getFirst();
        assertThat(demand.productionSourceBusinessId()).isEqualTo(sourceId);
        assertThat(demand.tasks()).isNotEmpty();
        assertThat(demand.tasks().getFirst().items())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.qualifiedQuantity()).isEqualTo(2);
                    assertThat(item.scrappedQuantity()).isZero();
                });

        asAdmin(() -> orderDeliveries.confirmShipment(
                new com.yumi.orders.api.ConfirmShipmentCommand(
                        uniqueKey("FLOW-SHIP"), order.orderBusinessId(),
                        java.util.List.of(new com.yumi.orders.api.ShipmentItemRequest(
                                groupId, sourceId, 2)),
                        "SF-FLOW-1", LocalDateTime.parse("2026-10-08T10:00:00"))));
        asAdmin(() -> cash.receiveOrder(new ReceiveOrderPaymentCommand(
                uniqueKey("FLOW-CASH"), order.orderBusinessId(),
                com.yumi.platform.api.value.SettlementMoney.of(
                        new java.math.BigDecimal("176.00"),
                        com.yumi.platform.api.value.CurrencyCode.CNY),
                java.time.LocalDate.parse("2026-10-08"))));
        asAdmin(() -> orderCompletions.complete(
                new com.yumi.orders.api.CompleteOrderCommand(
                        uniqueKey("FLOW-COMPLETE"), order.orderBusinessId())));

        var view = asAdmin(() -> ordersQuery.get(order.orderBusinessId()));
        assertThat(view.status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(view.deliveryFulfilled()).isTrue();
        assertThat(view.paymentFulfilled()).isTrue();
        assertThat(view.fulfillment().shippedQuantity()).isEqualTo(2);
        assertThat(view.fulfillment().receivedAmount().value()).isEqualByComparingTo("176.00");

        CashQuery.SourceCashSummary cash = asAdmin(() -> cashQuery.getSourceSummary(
                "orders", "ORDER", order.orderBusinessId()));
        assertThat(cash.receivedAmount()).isEqualByComparingTo("176.00");
        assertThat(cash.refundedAmount()).isEqualByComparingTo("0.00");
        assertThat(cash.paidAmount()).isEqualByComparingTo("0.00");
    }
}

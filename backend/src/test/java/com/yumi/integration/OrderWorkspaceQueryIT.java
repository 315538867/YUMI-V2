package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.yumi.orders.api.OrderQuery;
import com.yumi.orders.api.OrderRef;
import com.yumi.orders.api.OrderWorkspaceView;
import com.yumi.support.AbstractOrderFixtureTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 订单工作区读模型：发货、支付关联、凭证与时间线。 */
class OrderWorkspaceQueryIT extends AbstractOrderFixtureTest {

    @Autowired
    private OrderQuery orderQuery;

    @Test
    @DisplayName("工作区返回订单、发货明细、支付关联和完整时间线")
    void workspaceReturnsShipmentsPaymentsAndTimeline() {
        OrderRef order = completedOrder(2);

        OrderWorkspaceView view = asAdmin(() -> orderQuery.workspace(order.orderBusinessId()));

        assertThat(view.order().orderNo()).isEqualTo(order.orderNo());
        assertThat(view.shipments()).hasSize(1);
        assertThat(view.shipments().getFirst().valid()).isTrue();
        assertThat(view.shipments().getFirst().items()).hasSize(1);
        assertThat(view.shipments().getFirst().items().getFirst().orderItemBusinessId())
                .isNotBlank();
        assertThat(view.shipments().getFirst().items().getFirst().quantity()).isEqualTo(2);
        assertThat(view.shipments().getFirst().items().getFirst().effectiveQuantity()).isEqualTo(2);

        assertThat(view.payments()).hasSize(1);
        assertThat(view.payments().getFirst().financeCashFactBusinessId()).isNotBlank();
        assertThat(view.payments().getFirst().amount().value()).isEqualByComparingTo("176.00");

        assertThat(view.events()).anyMatch(event -> "CONFIRMATION".equals(event.kind()));
        assertThat(view.events()).anyMatch(event -> "SHIPMENT".equals(event.kind()));
        assertThat(view.events()).anyMatch(event -> "PAYMENT".equals(event.kind()));
        assertThat(view.events()).anyMatch(event -> "COMPLETION".equals(event.kind()));
    }
}

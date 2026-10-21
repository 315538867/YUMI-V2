package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.orders.api.CreateConfirmedOrderCommand;
import com.yumi.orders.api.OrderItemRequest;
import com.yumi.orders.api.OrderRef;
import com.yumi.orders.api.OrderStatus;
import com.yumi.orders.api.OrderView;
import com.yumi.platform.api.http.ApiException;
import com.yumi.product.api.MaterialSource;
import com.yumi.product.api.SchemeView;
import com.yumi.support.AbstractOrderFixtureTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 订单一次确认、客户与商品快照、最终交付供料依据和生产需求建立。
 *
 * <p>规则来源：`docs/一期/12-订单模块.md` §5、§7.1、§10。真实 MySQL 验证：
 * 命令成功即 CONFIRMED、任何参与端失败无半条订单、幂等重试复用成功结果。
 */
class OrderConfirmationIT extends AbstractOrderFixtureTest {

    @Autowired
    private com.yumi.orders.api.OrderQuery orderQuery;


    @Test
    @DisplayName("一次确认即生成 CONFIRMED 订单，并冻结客户/商品/方案快照与生产依据")
    void confirmationFreezesSnapshotsAndBasis() {
        OrderRef order = confirm(idem("IDEM-1"), 10);

        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.orderNo()).startsWith("SO");
        assertThat(order.totalQuantity()).isEqualTo(10);
        assertThat(order.totalAmount().value()).isEqualByComparingTo("880.00");

        OrderView view = asAdmin(() -> orderQuery.get(order.orderBusinessId()));
        assertThat(view.customerSnapshotRef()).isNotBlank();
        assertThat(view.items()).singleElement().satisfies(item -> {
            assertThat(item.orderedQuantity()).isEqualTo(10);
            assertThat(item.activeQuantity()).isEqualTo(10);
            assertThat(item.agreement().targetProcess()).isEqualTo("SEAM_CUTTING");
            assertThat(item.agreement().productionBasisFingerprint()).matches("[0-9a-f]{64}");
            assertThat(item.agreement().recipient().customerName()).isEqualTo("张三");
        });
        assertThat(view.fulfillment().deliveryFulfilled()).isFalse();
        assertThat(view.fulfillment().paymentFulfilled()).isFalse();
        assertThat(view.fulfillment().blockingReasons())
                .containsExactlyInAnyOrder("DELIVERY_PENDING", "PAYMENT_PENDING");

        // 生产需求随订单建立，并使用同一依据指纹
        assertThat(countDemandsOfOrder(order.orderBusinessId())).isEqualTo(1);
        assertThat(orderJdbc.sql("""
                        SELECT production_basis_fingerprint FROM production_demands
                        WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(String.class).single())
                .isEqualTo(view.items().getFirst().agreement().productionBasisFingerprint());
    }

    @Test
    @DisplayName("幂等重试复用成功结果，不重复建单或建需求")
    void idempotentRetryReusesResult() {
        String key = idem("IDEM-2");
        OrderRef first = confirm(key, 5);
        OrderRef second = confirm(key, 5);

        assertThat(second.orderBusinessId()).isEqualTo(first.orderBusinessId());
        assertThat(countOrders()).isEqualTo(1);
        assertThat(countDemandsOfOrder(first.orderBusinessId())).isEqualTo(1);
    }

    @Test
    @DisplayName("同键不同输入返回幂等冲突，不产生第二张订单")
    void sameKeyDifferentInputConflicts() {
        String key = idem("IDEM-3");
        confirm(key, 5);

        assertThatThrownBy(() -> confirm(key, 6))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).type())
                        .isEqualTo(com.yumi.platform.api.http.ApiErrorType.IDEMPOTENCY_CONFLICT));
        assertThat(countOrders()).isEqualTo(1);
    }

    @Test
    @DisplayName("命令缺少幂等键或订单项不完整时明确拒绝，不写任何数据")
    void invalidCommandsAreRejectedBeforeWriting() {
        assertThatThrownBy(() -> new CreateConfirmedOrderCommand(
                "", defaultCustomer.customerBusinessId(), null, ORDER_DATE, ORDER_DATE.plusDays(7),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new OrderItemRequest(
                defaultProduct.productBusinessId(), standardSchemeSnapshotRef(),
                SchemeView.ProcessCode.SEAM_CUTTING, 0,
                com.yumi.platform.api.value.SettlementMoney.of(
                        new java.math.BigDecimal("88.00"),
                        com.yumi.platform.api.value.CurrencyCode.CNY),
                new OrderItemRequest.MaterialSourceAgreement(
                        MaterialSource.STUDIO, MaterialSource.STUDIO,
                        MaterialSource.STUDIO, MaterialSource.STUDIO),
                new com.yumi.orders.api.RecipientInfo("张三", "13800000000", "地址")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("数量必须大于 0");

        assertThat(countOrders()).isZero();
    }

    @Test
    @DisplayName("多个订单项各自一个约定组与一个生产需求")
    void multipleItemsCreateOneDemandEach() {
        CreateConfirmedOrderCommand command = new CreateConfirmedOrderCommand(
                idem("IDEM-6"), defaultCustomer.customerBusinessId(), null, ORDER_DATE, ORDER_DATE.plusDays(3),
                List.of(item(2), item(3)));

        OrderRef order = asAdmin(() -> orders.createAndConfirm(command));
        assertThat(order.totalQuantity()).isEqualTo(5);
        assertThat(countDemandsOfOrder(order.orderBusinessId())).isEqualTo(2);
        assertThat(orderJdbc.sql("""
                        SELECT COUNT(*) FROM orders_agreement_groups g
                        JOIN orders_items i ON i.order_item_business_id = g.order_item_business_id
                        WHERE i.order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(Long.class).single())
                .isEqualTo(2);
    }

    private static String idem(String suffix) {
        return "IT-CONFIRM-" + java.util.UUID.randomUUID() + "-" + suffix;
    }

    private OrderItemRequest item(int quantity) {
        return new OrderItemRequest(
                defaultProduct.productBusinessId(), standardSchemeSnapshotRef(),
                SchemeView.ProcessCode.SEAM_CUTTING, quantity,
                com.yumi.platform.api.value.SettlementMoney.of(
                        new java.math.BigDecimal("10.00"),
                        com.yumi.platform.api.value.CurrencyCode.CNY),
                new OrderItemRequest.MaterialSourceAgreement(
                        MaterialSource.STUDIO, MaterialSource.STUDIO,
                        MaterialSource.STUDIO, MaterialSource.STUDIO),
                new com.yumi.orders.api.RecipientInfo("张三", "13800000000", "地址"));
    }
}

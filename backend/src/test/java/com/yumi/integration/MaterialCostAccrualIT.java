package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.yumi.finance.costledger.api.AccrueProductionMaterialCostsCommand;
import com.yumi.finance.costledger.api.CostLedgerApplicationService;
import com.yumi.orders.api.CreateConfirmedOrderCommand;
import com.yumi.orders.api.OrderItemRequest;
import com.yumi.orders.api.OrderRef;
import com.yumi.orders.api.RecipientInfo;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.product.api.MaterialSource;
import com.yumi.product.api.SchemeView;
import com.yumi.support.AbstractOrderFixtureTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 生产材料成本归集：当前工序工作室非人工成本、客户供料排除、来源唯一。
 *
 * <p>规则来源：`docs/一期/13-财务结算模块.md` §6、§8.5。真实 MySQL 验证：
 * 客户供料不入工作室成本，重复归集不重复入账。
 */
class MaterialCostAccrualIT extends AbstractOrderFixtureTest {

    @Autowired
    private CostLedgerApplicationService costLedger;

    @Test
    @DisplayName("工作室材料按来源归集，客户供料不入账，重复运行不重复")
    void studioMaterialsAccrueOnceAndCustomerSupplyIsExcluded() {
        OrderRef studioOrder = completedOrder(2);
        OrderRef customerOrder = completedCustomerSupplyOrder(2);

        var first = asAdmin(() -> costLedger.accrueProductionMaterialCosts(
                new AccrueProductionMaterialCostsCommand(
                        uniqueKey("COST-RUN"), LocalDate.parse("2026-10-01"),
                        LocalDate.parse("2026-10-31"))));
        assertThat(first.accruedCount()).isPositive();
        assertThat(first.failedCount()).isZero();
        long entries = orderJdbc.sql("""
                        SELECT COUNT(*) FROM finance_cost_entries
                        WHERE source_module = 'production' AND cost_category = 'MATERIAL'
                        """).query(Long.class).single();
        assertThat(entries).isEqualTo(first.accruedCount());
        assertThat(orderJdbc.sql("""
                        SELECT COUNT(*) FROM finance_cost_entries
                        WHERE subject_business_id = :orderId
                        """).param("orderId", studioOrder.orderBusinessId())
                .query(Long.class).single()).isPositive();
        assertThat(orderJdbc.sql("""
                        SELECT COUNT(*) FROM finance_cost_entries e
                        JOIN finance_cost_entry_lines l
                          ON l.cost_entry_business_id = e.cost_entry_business_id
                        WHERE e.subject_business_id = :orderId
                          AND l.component_code IN (
                              'GLUE', 'COLOR_PASTE', 'BAGGING_BAG', 'BAGGING_DECORATION')
                        """).param("orderId", customerOrder.orderBusinessId())
                .query(Long.class).single())
                .as("客户供料覆盖的胶水、色浆、袋子与装饰不计工作室成本")
                .isZero();
        assertThat(orderJdbc.sql("""
                        SELECT COUNT(*) FROM finance_cost_entries e
                        JOIN finance_cost_entry_lines l
                          ON l.cost_entry_business_id = e.cost_entry_business_id
                        WHERE e.subject_business_id = :orderId AND l.component_code = 'GLUE'
                        """).param("orderId", studioOrder.orderBusinessId())
                .query(Long.class).single())
                .as("工作室胶水正常归集")
                .isPositive();
        assertThat(orderJdbc.sql("""
                        SELECT SUM(amount) FROM finance_cost_entries
                        """).query(BigDecimal.class).single()).isPositive();
        assertThat(orderJdbc.sql("""
                        SELECT SUM(amount) FROM finance_cost_entry_lines
                        """).query(BigDecimal.class).single())
                .isEqualByComparingTo(orderJdbc.sql("""
                        SELECT SUM(amount) FROM finance_cost_entries
                        """).query(BigDecimal.class).single());

        var replay = asAdmin(() -> costLedger.accrueProductionMaterialCosts(
                new AccrueProductionMaterialCostsCommand(
                        uniqueKey("COST-RUN"), LocalDate.parse("2026-10-01"),
                        LocalDate.parse("2026-10-31"))));
        assertThat(replay.accruedCount()).isZero();
        assertThat(orderJdbc.sql("SELECT COUNT(*) FROM finance_cost_entries")
                .query(Long.class).single()).isEqualTo(entries);
        assertThat(orderJdbc.sql("SELECT COUNT(*) FROM finance_cost_source_guards")
                .query(Long.class).single()).isEqualTo(entries);
    }

    private OrderRef completedCustomerSupplyOrder(int quantity) {
        CreateConfirmedOrderCommand command = new CreateConfirmedOrderCommand(
                uniqueKey("ORDER"), defaultCustomer.customerBusinessId(), null, ORDER_DATE,
                ORDER_DATE.plusDays(7),
                List.of(new OrderItemRequest(
                        defaultProduct.productBusinessId(), standardSchemeSnapshotRef(),
                        SchemeView.ProcessCode.MAKING, quantity,
                        SettlementMoney.of(new BigDecimal("88.00"), CurrencyCode.CNY),
                        new OrderItemRequest.MaterialSourceAgreement(
                                MaterialSource.CUSTOMER, MaterialSource.CUSTOMER,
                                MaterialSource.CUSTOMER, MaterialSource.CUSTOMER),
                        new RecipientInfo("张三", "13800000000", "订单收货地址"))));
        OrderRef order = asAdmin(() -> orders.createAndConfirm(command));
        String sourceId = orderSourceId(order);
        produceAtMaking(sourceId, quantity);
        asAdmin(() -> orderDeliveries.confirmShipment(
                new com.yumi.orders.api.ConfirmShipmentCommand(
                        uniqueKey("SHIP"), order.orderBusinessId(),
                        List.of(new com.yumi.orders.api.ShipmentItemRequest(
                                orderGroupId(order), sourceId, quantity)),
                        "SF-COST", java.time.LocalDateTime.parse("2026-10-08T10:00:00"))));
        payOrder(order.orderBusinessId(), com.yumi.orders.api.OrderPaymentType.RECEIPT,
                new BigDecimal("88.00").multiply(BigDecimal.valueOf(quantity)).toPlainString());
        asAdmin(() -> orderCompletions.complete(
                new com.yumi.orders.api.CompleteOrderCommand(
                        uniqueKey("COMPLETE"), order.orderBusinessId())));
        return order;
    }
}

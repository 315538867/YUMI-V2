package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.orders.aftersales.api.AfterSalesApplicationService;
import com.yumi.orders.aftersales.api.AfterSalesCaseRef;
import com.yumi.orders.aftersales.api.AfterSalesCaseStatus;
import com.yumi.orders.aftersales.api.AfterSalesCaseView;
import com.yumi.orders.aftersales.api.AfterSalesCostService;
import com.yumi.orders.aftersales.api.ConfirmAfterSalesCostCommand;
import com.yumi.orders.aftersales.api.CorrectAfterSalesCostCommand;
import com.yumi.orders.aftersales.api.OrderAfterSalesCostSourceFeed;
import com.yumi.orders.aftersales.api.AfterSalesObligationRequest;
import com.yumi.orders.aftersales.api.AfterSalesObligationStatus;
import com.yumi.orders.aftersales.api.AfterSalesObligationType;
import com.yumi.orders.aftersales.api.AfterSalesQuery;
import com.yumi.orders.aftersales.api.AfterSalesRouteType;
import com.yumi.orders.aftersales.api.CompleteAfterSalesCaseCommand;
import com.yumi.orders.aftersales.api.OpenAfterSalesCaseCommand;
import com.yumi.orders.api.OrderRef;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.support.AbstractOrderFixtureTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 售后案例与义务：只从已完成订单受理，未完成义务不能结案。
 *
 * <p>规则来源：`docs/一期/12-订单模块.md` §13.1、§13.2。真实 MySQL 验证：
 * 未完成订单拒绝受理，问题事实与三类义务分别保存，结案被未完成义务阻止。
 */
class AfterSalesCaseIT extends AbstractOrderFixtureTest {

    @Autowired
    private AfterSalesApplicationService afterSales;

    @Autowired
    private AfterSalesQuery afterSalesQuery;

    @Autowired
    private AfterSalesCostService afterSalesCosts;

    @Autowired
    private OrderAfterSalesCostSourceFeed afterSalesCostFeed;

    @Test
    @DisplayName("未完成订单不能受理售后，也不留下案例")
    void unfinishedOrderIsRejected() {
        OrderRef order = confirmOrderForMaking(2);

        assertThatThrownBy(() -> asAdmin(() -> afterSales.open(command(order))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("AFTER_SALES_ORDER_NOT_COMPLETED"));
        assertThat(count("after_sales_cases")).isZero();
        assertThat(count("after_sales_issue_facts")).isZero();
    }

    @Test
    @DisplayName("受理冻结订单、问题事实和客户配合/数量/金额义务")
    void openStoresCaseAndIndependentObligations() {
        OrderRef order = completedOrder(3);

        AfterSalesCaseRef caseRef = asAdmin(() -> afterSales.open(command(order)));

        assertThat(caseRef.caseNo()).startsWith("AS");
        assertThat(caseRef.status()).isEqualTo(AfterSalesCaseStatus.OPEN);
        assertThat(count("after_sales_cases")).isEqualTo(1);
        assertThat(count("after_sales_issue_facts")).isEqualTo(1);
        assertThat(count("after_sales_obligations")).isEqualTo(3);

        AfterSalesCaseView view = asAdmin(() -> afterSalesQuery.get(caseRef.caseBusinessId()));
        assertThat(view.orderBusinessId()).isEqualTo(order.orderBusinessId());
        assertThat(view.customerBusinessId()).isEqualTo(defaultCustomer.customerBusinessId());
        assertThat(view.routeType()).isEqualTo(AfterSalesRouteType.MIXED);
        assertThat(view.completedAt()).isNull();
        assertThat(view.obligations()).hasSize(3);
        assertThat(view.obligations())
                .extracting(AfterSalesCaseView.ObligationView::obligationType,
                        AfterSalesCaseView.ObligationView::status)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                AfterSalesObligationType.CUSTOMER_COOPERATION,
                                AfterSalesObligationStatus.PENDING),
                        org.assertj.core.groups.Tuple.tuple(
                                AfterSalesObligationType.RETURN_AND_REPAIR,
                                AfterSalesObligationStatus.PENDING),
                        org.assertj.core.groups.Tuple.tuple(
                                AfterSalesObligationType.REFUND,
                                AfterSalesObligationStatus.PENDING));
    }

    @Test
    @DisplayName("存在未完成义务时拒绝结案，原案例保持打开")
    void completionBlockedByUnfinishedObligations() {
        OrderRef order = completedOrder(2);
        AfterSalesCaseRef caseRef = asAdmin(() -> afterSales.open(command(order)));

        assertThatThrownBy(() -> asAdmin(() -> afterSales.complete(
                new CompleteAfterSalesCaseCommand(uniqueKey("AS-COMPLETE"),
                        caseRef.caseBusinessId()))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> {
                    assertThat(((ApiException) exception).code())
                            .isEqualTo("AFTER_SALES_OBLIGATION_NOT_READY");
                    assertThat(((ApiException) exception).getMessage())
                            .contains("CUSTOMER_COOPERATION", "RETURN_AND_REPAIR", "REFUND");
                });
        assertThat(count("after_sales_completion_facts")).isZero();
        assertThat(asAdmin(() -> afterSalesQuery.get(caseRef.caseBusinessId())).status())
                .isEqualTo(AfterSalesCaseStatus.OPEN);
    }

    @Test
    @DisplayName("售后费用确认与更正发布不可变来源，未结义务仍阻止结案")
    void costConfirmationAndCorrectionAreIndependentFromSettlement() {
        OrderRef order = completedOrder(2);
        AfterSalesCaseRef caseRef = asAdmin(() -> afterSales.open(command(order)));

        asAdmin(() -> afterSalesCosts.confirmCost(new ConfirmAfterSalesCostCommand(
                uniqueKey("AS-COST"), caseRef.caseBusinessId(), "LABOR",
                SettlementMoney.of(new BigDecimal("80.00"), CurrencyCode.CNY),
                LocalDate.parse("2026-10-13"), "返修人工")));
        // 更正必须引用真实来源
        assertThatThrownBy(() -> asAdmin(() -> afterSalesCosts.correctCost(
                new CorrectAfterSalesCostCommand(
                        uniqueKey("AS-COST-CORRECT"), caseRef.caseBusinessId(), "ASC-MISSING",
                        SettlementMoney.of(new BigDecimal("50.00"), CurrencyCode.CNY),
                        LocalDate.parse("2026-10-14"), "人工复核下调"))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("AFTER_SALES_COST_NOT_FOUND"));
        var feed = afterSalesCostFeed.listAfter(null, 10);
        assertThat(feed.items()).hasSize(1);
        assertThat(feed.items().getFirst().operationType()).isEqualTo("ACCRUE");
        assertThat(feed.items().getFirst().amount()).isEqualByComparingTo("80.00");

        String sourceId = feed.items().getFirst().sourceBusinessId();
        asAdmin(() -> afterSalesCosts.correctCost(new CorrectAfterSalesCostCommand(
                uniqueKey("AS-COST-CORRECT"), caseRef.caseBusinessId(), sourceId,
                SettlementMoney.of(new BigDecimal("50.00"), CurrencyCode.CNY),
                LocalDate.parse("2026-10-14"), "人工复核下调")));
        var corrected = afterSalesCostFeed.listAfter(null, 10);
        assertThat(corrected.items())
                .extracting(OrderAfterSalesCostSourceFeed.AfterSalesCostSourceItem::operationType,
                        OrderAfterSalesCostSourceFeed.AfterSalesCostSourceItem::sourceVersion,
                        OrderAfterSalesCostSourceFeed.AfterSalesCostSourceItem::amount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("ACCRUE", 1, new BigDecimal("80.00")),
                        org.assertj.core.groups.Tuple.tuple("REVERSE", 1, new BigDecimal("80.00")),
                        org.assertj.core.groups.Tuple.tuple("ACCRUE", 2, new BigDecimal("50.00")));

        // 费用确认不改变客户义务结案条件
        assertThatThrownBy(() -> asAdmin(() -> afterSales.complete(
                new CompleteAfterSalesCaseCommand(uniqueKey("AS-COMPLETE"),
                        caseRef.caseBusinessId()))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("AFTER_SALES_OBLIGATION_NOT_READY"));
    }

    @Test
    @DisplayName("同一受理命令重试复用原案例")
    void openIsIdempotent() {
        OrderRef order = completedOrder(2);
        OpenAfterSalesCaseCommand command = command(order);

        AfterSalesCaseRef first = asAdmin(() -> afterSales.open(command));
        AfterSalesCaseRef second = asAdmin(() -> afterSales.open(command));

        assertThat(second.caseBusinessId()).isEqualTo(first.caseBusinessId());
        assertThat(count("after_sales_cases")).isEqualTo(1);
        assertThat(count("after_sales_obligations")).isEqualTo(3);
    }

    private OpenAfterSalesCaseCommand command(OrderRef order) {
        String itemId = orderJdbc.sql("""
                        SELECT order_item_business_id FROM orders_items WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(String.class).single();
        return new OpenAfterSalesCaseCommand(
                uniqueKey("AS-OPEN"), order.orderBusinessId(), "客户反馈缝边开裂",
                AfterSalesRouteType.MIXED,
                List.of(
                        new AfterSalesObligationRequest(
                                AfterSalesObligationType.CUSTOMER_COOPERATION,
                                null, null, LocalDate.parse("2026-10-12"), "客户寄回"),
                        new AfterSalesObligationRequest(
                                AfterSalesObligationType.RETURN_AND_REPAIR,
                                2, null, LocalDate.parse("2026-10-15"), "返修 2 件", itemId),
                        new AfterSalesObligationRequest(
                                AfterSalesObligationType.REFUND,
                                null,
                                SettlementMoney.of(new BigDecimal("50.00"), CurrencyCode.CNY),
                                LocalDate.parse("2026-10-15"), "退还差价")));
    }

    private long count(String table) {
        return orderJdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}

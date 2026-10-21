package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.orders.api.OrderRef;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.identity.infrastructure.CurrentActorHolder;
import com.yumi.reporting.api.IngestReportingSourcesCommand;
import com.yumi.reporting.api.OperatingReportQuery;
import com.yumi.reporting.api.OperatingReportQuery.MonthlyOperatingSummary;
import com.yumi.reporting.api.ReportingIngestionService;
import com.yumi.support.AbstractOrderFixtureTest;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 月度经营汇总：应收、生产、成本、工资与现金分列。
 *
 * <p>规则来源：`docs/一期/14-报表模块.md` §4.1、§6。真实 MySQL 验证：
 * 报废与转线下分别展示，工资退回不充当经营收入，成本缺失时提示不完整，现金净额不叫利润。
 */
class OperatingSummaryIT extends AbstractOrderFixtureTest {

    private static final LocalDate SEPTEMBER = LocalDate.parse("2026-09-01");

    @Autowired
    private OperatingReportQuery reports;

    @Autowired
    private ReportingIngestionService ingestion;

    @Test
    @DisplayName("真实来源归集后月汇总区分应收与净收款，并提示成本缺失")
    void monthlySummaryUsesRealFeedPayloads() {
        completedOrder(2);
        OrderRef unpaid = confirmOrderForMaking(3);
        asAdmin(() -> ingestion.ingest(
                new IngestReportingSourcesCommand(uniqueKey("REPORT-INGEST"))));

        MonthlyOperatingSummary summary = asAdmin(
                () -> reports.getMonthlySummary(LocalDate.parse("2026-10-01")));

        assertThat(summary.orderCount()).isEqualTo(2);
        assertThat(summary.orderReceivable()).isEqualByComparingTo("440.00");
        assertThat(summary.orderReceipts()).isEqualByComparingTo("0.00");
        assertThat(summary.orderNetReceipts()).isEqualByComparingTo("0.00");
        assertThat(summary.cashInflow()).isEqualByComparingTo("0.00");
        assertThat(summary.cashOutflow()).isEqualByComparingTo("0.00");
        assertThat(summary.cashNet()).isEqualByComparingTo("0.00");
        assertThat(summary.productionRequiredQuantity()).isEqualTo(5);
        assertThat(summary.productionFulfilledQuantity()).isEqualTo(2);
        assertThat(summary.costTotal()).isEqualByComparingTo("0.00");
        assertThat(summary.costIncomplete()).isTrue();
        assertThat(summary.payrollPayable()).isEqualByComparingTo("0.00");
        assertThat(summary.lateSources()).isFalse();
        assertThat(unpaid.orderBusinessId()).isNotBlank();
    }

    @Test
    @DisplayName("报废与转线下分列，工资退回不充当经营收入，成本与现金流不合并")
    void monthlySummarySeparatesProductionPayrollCostAndCash() {
        projection("ORDER", "ORD-SEP", """
                {"orderDate":"2026-09-10","currentReceivable":100.00}
                """);
        projection("PRODUCTION", "DEM-SEP", """
                {"requiredQuantity":10,"fulfilledQuantity":6,"fulfillmentGapQuantity":4,
                 "inSystemQuantity":2,"consumedQuantity":1,"destroyedQuantity":3,
                 "offlineInventoryQuantity":1,"updatedAt":"2026-09-15T08:00:00Z"}
                """);
        projection("COST", "CST-SEP", """
                {"businessDate":"2026-09-20","amount":55.50}
                """);
        projection("PAYROLL", "PAY-SEP", """
                {"periodStartDate":"2026-09-01","payableAmount":1000.00,
                 "paidAmount":400.00,"updatedAt":"2026-10-05T08:00:00Z"}
                """);
        projection("CASH", "CSH-RECEIPT", cash("IN", "ORDER_RECEIPT", "2026-09-05", "100.00"));
        projection("CASH", "CSH-REFUND", cash("OUT", "ORDER_REFUND", "2026-09-06", "20.00"));
        projection("CASH", "CSH-AFTERSALES",
                cash("OUT", "AFTER_SALES_REFUND", "2026-09-07", "30.00"));
        projection("CASH", "CSH-PAYROLL",
                cash("OUT", "PAYROLL_PAYMENT", "2026-09-08", "400.00"));
        projection("CASH", "CSH-RETURN", cash("IN", "PAYROLL_RETURN", "2026-09-09", "100.00"));
        projection("CASH", "CSH-INCOME", cash("IN", "DAILY_INCOME", "2026-09-10", "50.00"));
        projection("CASH", "CSH-EXPENSE", cash("OUT", "DAILY_EXPENSE", "2026-09-11", "40.00"));
        projection("CASH", "CSH-VOID", """
                {"businessDate":"2026-09-12","direction":"IN","cashType":"ORDER_RECEIPT",
                 "amount":999.00,"valid":false}
                """);
        projection("CASH", "CSH-OCT", cash("IN", "ORDER_RECEIPT", "2026-10-01", "200.00"));

        MonthlyOperatingSummary summary = asAdmin(() -> reports.getMonthlySummary(SEPTEMBER));

        assertThat(summary.orderCount()).isEqualTo(1);
        assertThat(summary.orderReceivable()).isEqualByComparingTo("100.00");
        assertThat(summary.productionRequiredQuantity()).isEqualTo(10);
        assertThat(summary.productionFulfilledQuantity()).isEqualTo(6);
        assertThat(summary.productionGapQuantity()).isEqualTo(4);
        assertThat(summary.productionInSystemQuantity()).isEqualTo(2);
        assertThat(summary.productionConsumedQuantity()).isEqualTo(1);
        assertThat(summary.destroyedQuantity()).isEqualTo(3);
        assertThat(summary.offlineInventoryQuantity()).isEqualTo(1);
        assertThat(summary.costTotal()).isEqualByComparingTo("55.50");
        assertThat(summary.costIncomplete()).isFalse();
        assertThat(summary.payrollPayable()).isEqualByComparingTo("1000.00");
        assertThat(summary.payrollPaid()).isEqualByComparingTo("400.00");
        assertThat(summary.payrollCashReturn()).isEqualByComparingTo("100.00");
        assertThat(summary.operatingIncome()).isEqualByComparingTo("50.00");
        assertThat(summary.operatingExpense()).isEqualByComparingTo("40.00");
        assertThat(summary.orderReceipts()).isEqualByComparingTo("100.00");
        assertThat(summary.orderRefunds()).isEqualByComparingTo("20.00");
        assertThat(summary.orderNetReceipts()).isEqualByComparingTo("80.00");
        assertThat(summary.afterSalesPayments()).isEqualByComparingTo("30.00");
        assertThat(summary.cashInflow()).isEqualByComparingTo("250.00");
        assertThat(summary.cashOutflow()).isEqualByComparingTo("490.00");
        assertThat(summary.cashNet()).isEqualByComparingTo("-240.00");
        assertThat(summary.lateSources()).isFalse();

        MonthlyOperatingSummary october = asAdmin(
                () -> reports.getMonthlySummary(LocalDate.parse("2026-10-18")));
        assertThat(october.monthStart()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(october.orderCount()).isZero();
        assertThat(october.costIncomplete()).isFalse();
        assertThat(october.payrollPayable()).isEqualByComparingTo("0.00");
        assertThat(october.orderReceipts()).isEqualByComparingTo("200.00");

        orderJdbc.sql("""
                        INSERT INTO reporting_feed_cursors (feed_code, status, updated_at)
                        VALUES ('ORDER', 'FAILED', UTC_TIMESTAMP(6))
                        """).update();
        assertThat(asAdmin(() -> reports.getMonthlySummary(SEPTEMBER)).lateSources()).isTrue();
    }

    @Test
    @DisplayName("订单存在但当月没有成本时提示不完整")
    void missingCostMarksSummaryIncomplete() {
        projection("ORDER", "ORD-NOV", """
                {"orderDate":"2026-11-03","currentReceivable":88.00}
                """);
        projection("COST", "CST-OCT", """
                {"businessDate":"2026-10-31","amount":10.00}
                """);

        MonthlyOperatingSummary summary = asAdmin(
                () -> reports.getMonthlySummary(LocalDate.parse("2026-11-01")));

        assertThat(summary.orderCount()).isEqualTo(1);
        assertThat(summary.costTotal()).isEqualByComparingTo("0.00");
        assertThat(summary.costIncomplete()).isTrue();
    }

    @Test
    @DisplayName("未认证或缺少报表读取权限时拒绝查询")
    void readingRequiresAuthorization() {
        assertThatThrownBy(() -> CurrentActorHolder.runAs(null,
                () -> reports.getMonthlySummary(SEPTEMBER)))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).type())
                        .isEqualTo(ApiErrorType.FORBIDDEN));

        assertThatThrownBy(() -> asAdmin(() -> reports.getMonthlySummary(null)))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).type())
                        .isEqualTo(ApiErrorType.VALIDATION));
    }

    private void projection(String feed, String sourceId, String payload) {
        orderJdbc.sql("""
                        INSERT INTO reporting_projection_records (
                            feed_code, source_business_id, source_version, payload_json,
                            occurred_at, updated_at)
                        VALUES (:feed, :sourceId, 1, :payload, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                        """)
                .param("feed", feed)
                .param("sourceId", sourceId)
                .param("payload", payload)
                .update();
    }

    private static String cash(
            String direction, String cashType, String businessDate, String amount) {
        return """
                {"businessDate":"%s","direction":"%s","cashType":"%s","amount":%s,"valid":true}
                """.formatted(businessDate, direction, cashType, amount);
    }
}

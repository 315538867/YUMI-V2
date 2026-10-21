package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.yumi.customer.api.CustomerReportingFeed;
import com.yumi.finance.cash.api.CashApplicationService;
import com.yumi.finance.cash.api.ReceiveOrderPaymentCommand;
import com.yumi.finance.costledger.api.AccrueProductionMaterialCostsCommand;
import com.yumi.finance.costledger.api.CostLedgerApplicationService;
import com.yumi.orders.api.OrderRef;
import com.yumi.orders.api.OrderStatus;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.idempotency.IdempotentCommandService;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.product.api.ProductReportingFeed;
import com.yumi.production.api.ProductionReportingFeed;
import com.yumi.reporting.api.IngestReportingSourcesCommand;
import com.yumi.reporting.api.OperatingReportQuery;
import com.yumi.reporting.api.RebuildReportingProjectionCommand;
import com.yumi.reporting.api.ReportingIngestionService;
import com.yumi.reporting.api.ReportingRebuildService;
import com.yumi.reporting.application.ReportingIngestionServiceImpl;
import com.yumi.orders.api.OrderReportingFeed;
import com.yumi.orders.aftersales.api.AfterSalesReportingFeed;
import com.yumi.finance.payroll.api.PayrollReportingFeed;
import com.yumi.finance.costledger.api.CostReportingFeed;
import com.yumi.finance.cash.api.CashReportingFeed;
import com.yumi.workforce.api.WorkforceReportingFeed;
import com.yumi.support.AbstractOrderFixtureTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 报表重复消费、失败恢复、全量重建与订单/成本/现金对账。
 *
 * <p>规则来源：`docs/一期/14-报表模块.md` §4.1、§6、§8。真实 MySQL：
 * 重复归集不重复、失败只影响单个来源、重建切换后月汇总与权威事实一致。
 */
class ReportingReconciliationIT extends AbstractOrderFixtureTest {

    @Autowired
    private ReportingIngestionService ingestion;

    @Autowired
    private ReportingRebuildService rebuilds;

    @Autowired
    private OperatingReportQuery reports;

    @Autowired
    private CashApplicationService cash;

    @Autowired
    private CostLedgerApplicationService costs;

    @Autowired
    private CustomerReportingFeed customerFeed;

    @Autowired
    private ProductReportingFeed productFeed;

    @Autowired
    private WorkforceReportingFeed workforceFeed;

    @Autowired
    private ProductionReportingFeed productionFeed;

    @Autowired
    private OrderReportingFeed orderFeed;

    @Autowired
    private AfterSalesReportingFeed afterSalesFeed;

    @Autowired
    private PayrollReportingFeed payrollFeed;

    @Autowired
    private CostReportingFeed costFeed;

    @Autowired
    private CashReportingFeed cashFeed;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IdempotentCommandService idempotency;

    @Autowired
    private AuthorizationService authorization;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("重复归集不重复、失败恢复、重建后应收/成本/现金对账一致")
    void reportingReconciliationAndRebuild() {
        OrderRef order = confirmOrderForMaking(1);
        String sourceId = orderSourceId(order);
        produceAtMaking(sourceId, 1);
        asAdmin(() -> cash.receiveOrder(new ReceiveOrderPaymentCommand(
                uniqueKey("RECON-CASH"), order.orderBusinessId(),
                SettlementMoney.of(new BigDecimal("88.00"), CurrencyCode.CNY),
                LocalDate.parse("2026-10-08"))));
        asAdmin(() -> costs.accrueProductionMaterialCosts(
                new AccrueProductionMaterialCostsCommand(
                        uniqueKey("RECON-COST"),
                        LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"))));

        var first = asAdmin(() -> ingestion.ingest(
                new IngestReportingSourcesCommand(uniqueKey("RECON-INGEST"))));
        assertThat(first.failureCount()).isZero();
        long records = count("reporting_projection_records");
        asAdmin(() -> ingestion.ingest(
                new IngestReportingSourcesCommand(uniqueKey("RECON-INGEST"))));
        assertThat(count("reporting_projection_records")).isEqualTo(records);

        var summary = asAdmin(() -> reports.getMonthlySummary(LocalDate.parse("2026-10-01")));
        assertThat(summary.orderCount()).isEqualTo(1);
        assertThat(summary.orderReceivable()).isEqualByComparingTo("88.00");
        assertThat(summary.orderReceipts()).isEqualByComparingTo("88.00");
        assertThat(summary.cashInflow()).isEqualByComparingTo("88.00");
        assertThat(summary.costTotal()).isPositive();

        var failed = new ReportingIngestionServiceImpl(
                jdbc, failingCustomer(), productFeed, workforceFeed, productionFeed, orderFeed,
                afterSalesFeed, payrollFeed, costFeed, cashFeed, objectMapper, idempotency,
                authorization, clock, transactionManager);
        var isolated = asAdmin(() -> failed.ingest(
                new IngestReportingSourcesCommand(uniqueKey("RECON-INGEST"))));
        assertThat(isolated.failureCount()).isEqualTo(1);
        assertThat(cursorStatus("CUSTOMER")).isEqualTo("FAILED");
        assertThat(cursorStatus("ORDER")).isEqualTo("READY");
        asAdmin(() -> ingestion.ingest(
                new IngestReportingSourcesCommand(uniqueKey("RECON-INGEST"))));
        assertThat(cursorStatus("CUSTOMER")).isEqualTo("READY");

        var version = asAdmin(() -> rebuilds.rebuild(
                new RebuildReportingProjectionCommand(uniqueKey("RECON-REBUILD"))));
        assertThat(version.activated()).isTrue();
        assertThat(version.differenceCount()).isZero();
        var afterRebuild = asAdmin(
                () -> reports.getMonthlySummary(LocalDate.parse("2026-10-01")));
        assertThat(afterRebuild.orderReceivable()).isEqualByComparingTo(summary.orderReceivable());
        assertThat(afterRebuild.costTotal()).isEqualByComparingTo(summary.costTotal());
        assertThat(afterRebuild.cashInflow()).isEqualByComparingTo(summary.cashInflow());
        assertThat(afterRebuild.orderCount()).isEqualTo(1);

        var view = asAdmin(() -> ordersQuery().get(order.orderBusinessId()));
        assertThat(view.status()).isIn(OrderStatus.CONFIRMED, OrderStatus.IN_FULFILLMENT);
    }

    @Autowired
    private com.yumi.orders.api.OrderQuery orderQuery;

    private com.yumi.orders.api.OrderQuery ordersQuery() {
        return orderQuery;
    }

    private CustomerReportingFeed failingCustomer() {
        return (cursor, limit) -> {
            throw new IllegalStateException("注入客户来源失败");
        };
    }

    private String cursorStatus(String feed) {
        return jdbc.sql("SELECT status FROM reporting_feed_cursors WHERE feed_code = :feed")
                .param("feed", feed).query(String.class).single();
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}

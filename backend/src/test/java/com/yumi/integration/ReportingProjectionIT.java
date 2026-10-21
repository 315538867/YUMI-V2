package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.customer.api.CustomerReportingFeed;
import com.yumi.finance.cash.api.CashReportingFeed;
import com.yumi.finance.costledger.api.CostReportingFeed;
import com.yumi.finance.payroll.api.PayrollReportingFeed;
import com.yumi.orders.aftersales.api.AfterSalesReportingFeed;
import com.yumi.orders.api.OrderRef;
import com.yumi.orders.api.OrderReportingFeed;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.idempotency.IdempotentCommandService;
import com.yumi.product.api.ProductReportingFeed;
import com.yumi.production.api.ProductionReportingFeed;
import com.yumi.reporting.api.IngestReportingSourcesCommand;
import com.yumi.reporting.api.ReportingIngestionService;
import com.yumi.reporting.application.ReportingIngestionServiceImpl;
import com.yumi.support.AbstractOrderFixtureTest;
import com.yumi.workforce.api.WorkforceReportingFeed;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 报表来源归集：独立水位、失败可定位、不阻塞业务、不跨模块 SQL。
 *
 * <p>规则来源：`docs/一期/14-报表模块.md` §6、§8。真实 MySQL 验证：
 * 每个 Feed 独立游标，失败不推进该 Feed 水位，其他 Feed 继续。
 */
class ReportingProjectionIT extends AbstractOrderFixtureTest {

    @Autowired
    private ReportingIngestionService ingestion;

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
    @DisplayName("九个 Feed 独立水位，重复归集不重复；单个 Feed 失败不阻塞其他来源")
    void ingestionIsIsolatedAndIdempotent() {
        OrderRef order = completedOrder(2);

        var first = asAdmin(() -> ingestion.ingest(
                new IngestReportingSourcesCommand(uniqueKey("REPORT-INGEST"))));
        assertThat(first.failureCount()).isZero();
        assertThat(first.recordCount()).isPositive();
        assertThat(count("reporting_feed_cursors")).isEqualTo(9);
        assertThat(count("reporting_feed_leases")).isZero();
        assertThat(recordsOf("CUSTOMER")).isPositive();
        assertThat(recordsOf("PRODUCT")).isPositive();
        assertThat(recordsOf("WORKFORCE")).isPositive();
        assertThat(recordsOf("PRODUCTION")).isPositive();
        assertThat(recordsOf("ORDER")).isPositive();
        long before = count("reporting_projection_records");

        asAdmin(() -> ingestion.ingest(
                new IngestReportingSourcesCommand(uniqueKey("REPORT-INGEST"))));
        assertThat(count("reporting_projection_records")).isEqualTo(before);
        assertThat(count("reporting_feed_failures")).isZero();

        // 注入客户 Feed 失败：只影响客户水位，其他来源继续，业务事实不受影响
        CustomerReportingFeed failingCustomer = (cursor, limit) -> {
            throw new IllegalStateException("注入客户来源故障");
        };
        var isolated = new ReportingIngestionServiceImpl(
                jdbc, failingCustomer, productFeed, workforceFeed, productionFeed, orderFeed,
                afterSalesFeed, payrollFeed, costFeed, cashFeed, objectMapper, idempotency,
                authorization, clock, transactionManager);
        var result = asAdmin(() -> isolated.ingest(
                new IngestReportingSourcesCommand(uniqueKey("REPORT-INGEST"))));
        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(count("reporting_feed_failures")).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT status FROM reporting_feed_cursors WHERE feed_code = 'CUSTOMER'
                        """).query(String.class).single()).isEqualTo("FAILED");
        assertThat(jdbc.sql("""
                        SELECT status FROM reporting_feed_cursors WHERE feed_code = 'PRODUCTION'
                        """).query(String.class).single()).isEqualTo("READY");
        assertThat(orderJdbc.sql("""
                        SELECT COUNT(*) FROM orders_orders WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(Long.class).single())
                .isEqualTo(1);

        // 恢复后重试，客户水位恢复 READY，不重复投影
        asAdmin(() -> ingestion.ingest(
                new IngestReportingSourcesCommand(uniqueKey("REPORT-INGEST"))));
        assertThat(jdbc.sql("""
                        SELECT status FROM reporting_feed_cursors WHERE feed_code = 'CUSTOMER'
                        """).query(String.class).single()).isEqualTo("READY");
        assertThat(count("reporting_projection_records")).isEqualTo(before);
    }

    private long recordsOf(String feed) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM reporting_projection_records WHERE feed_code = :feed
                        """).param("feed", feed).query(Long.class).single();
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}

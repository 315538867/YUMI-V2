package com.yumi.reporting.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.customer.api.CustomerReportingFeed;
import com.yumi.finance.cash.api.CashReportingFeed;
import com.yumi.finance.costledger.api.CostReportingFeed;
import com.yumi.finance.payroll.api.PayrollReportingFeed;
import com.yumi.orders.aftersales.api.AfterSalesReportingFeed;
import com.yumi.orders.api.OrderReportingFeed;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.idempotency.IdempotentCommandService;
import com.yumi.product.api.ProductReportingFeed;
import com.yumi.production.api.ProductionReportingFeed;
import com.yumi.reporting.api.OperatingReportQuery;
import com.yumi.reporting.api.ReportingIngestionService;
import com.yumi.reporting.api.ReportingQuery;
import com.yumi.reporting.application.OperatingReportQueryImpl;
import com.yumi.reporting.application.ReportingIngestionServiceImpl;
import com.yumi.reporting.application.ReportingQueryImpl;
import com.yumi.workforce.api.WorkforceReportingFeed;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/** 报表模块装配。 */
@Configuration
public class ReportingConfiguration {

    @Bean(name = "reportingRebuildService")
    public com.yumi.reporting.api.ReportingRebuildService reportingRebuildService(
            com.yumi.reporting.application.ReportingRebuildRepository rebuildRepository,
            IdempotentCommandService idempotency,
            AuthorizationService authorization,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new com.yumi.reporting.application.ReportingRebuildServiceImpl(
                rebuildRepository, idempotency, authorization, clock, transactionManager);
    }

    @Bean(name = "operatingReportQuery")
    public OperatingReportQuery operatingReportQuery(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            AuthorizationService authorization) {
        return new OperatingReportQueryImpl(jdbcClient, objectMapper, authorization);
    }

    @Bean(name = "reportingQuery")
    public ReportingQuery reportingQuery(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            OperatingReportQuery operatingReportQuery,
            AuthorizationService authorization,
            Clock clock) {
        return new ReportingQueryImpl(
                jdbcClient, objectMapper, operatingReportQuery, authorization, clock);
    }

    @Bean(name = "reportingIngestionService")
    public ReportingIngestionService reportingIngestionService(
            JdbcClient jdbcClient,
            CustomerReportingFeed customerReportingFeed,
            ProductReportingFeed productReportingFeed,
            WorkforceReportingFeed workforceReportingFeed,
            ProductionReportingFeed productionReportingFeed,
            OrderReportingFeed orderReportingFeed,
            AfterSalesReportingFeed afterSalesReportingFeed,
            PayrollReportingFeed payrollReportingFeed,
            CostReportingFeed costReportingFeed,
            CashReportingFeed cashReportingFeed,
            ObjectMapper objectMapper,
            IdempotentCommandService idempotency,
            AuthorizationService authorization,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new ReportingIngestionServiceImpl(
                jdbcClient, customerReportingFeed, productReportingFeed, workforceReportingFeed,
                productionReportingFeed, orderReportingFeed, afterSalesReportingFeed,
                payrollReportingFeed, costReportingFeed, cashReportingFeed, objectMapper,
                idempotency, authorization, clock, transactionManager);
    }
}

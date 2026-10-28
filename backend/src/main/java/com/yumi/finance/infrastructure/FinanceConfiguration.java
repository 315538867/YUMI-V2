package com.yumi.finance.infrastructure;

import com.yumi.finance.payroll.api.PayrollSourceIngestionService;
import com.yumi.finance.payroll.application.PayrollSourceIngestionServiceImpl;
import com.yumi.finance.payroll.application.PayrollSourceRepository;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.idempotency.IdempotentCommandService;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.production.api.ProductionPayrollSourceFeed;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** 财务模块装配：只注册公开子域 API。 */
@Configuration
public class FinanceConfiguration {

    @Bean(name = "cashApplicationService")
    public com.yumi.finance.cash.api.CashApplicationService cashApplicationService(
            com.yumi.finance.cash.application.CashRepository cashRepository,
            com.yumi.orders.api.OrderPaymentParticipant orderPaymentParticipant,
            com.yumi.orders.aftersales.api.AfterSalesPaymentParticipant afterSalesPaymentParticipant,
            com.yumi.finance.payroll.api.PayrollPaymentParticipant payrollPaymentParticipant,
            com.yumi.finance.costledger.api.OperatingExpenseService operatingExpenseService,
            com.yumi.platform.api.file.FileReferenceParticipant fileReferenceParticipant,
            com.yumi.platform.api.numbering.BusinessNumberService numbering,
            IdempotentCommandService idempotency,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            com.yumi.platform.api.audit.AuditService audit,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new com.yumi.finance.cash.application.CashApplicationServiceImpl(
                cashRepository, orderPaymentParticipant, afterSalesPaymentParticipant,
                payrollPaymentParticipant, operatingExpenseService, fileReferenceParticipant,
                numbering, idempotency, authorization, currentActorProvider, audit, clock,
                transactionManager);
    }

    @Bean(name = "costLedgerApplicationService")
    public com.yumi.finance.costledger.api.CostLedgerApplicationService costLedgerApplicationService(
            com.yumi.finance.costledger.application.CostLedgerRepository costLedgerRepository,
            com.yumi.production.api.ProductionPayrollMaterialSourceFeed payrollMaterialFeed,
            com.yumi.workforce.api.WorkforceEmployeeDirectory workforceEmployeeDirectory,
            com.yumi.finance.payroll.api.PayrollCostSourceFeed payrollCostFeed,
            com.yumi.orders.aftersales.api.OrderAfterSalesCostSourceFeed afterSalesCostFeed,
            IdempotentCommandService idempotency,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new com.yumi.finance.costledger.application.CostLedgerApplicationServiceImpl(
                costLedgerRepository, payrollMaterialFeed, payrollCostFeed, afterSalesCostFeed,
                idempotency, authorization, currentActorProvider, clock, transactionManager);
    }

    @Bean(name = "cashReportingFeed")
    public com.yumi.finance.cash.api.CashReportingFeed cashReportingFeed(
            org.springframework.jdbc.core.simple.JdbcClient jdbcClient) {
        return new com.yumi.finance.cash.infrastructure.CashReportingFeedImpl(jdbcClient);
    }

    @Bean(name = "cashQuery")
    public com.yumi.finance.cash.api.CashQuery cashQuery(
            org.springframework.jdbc.core.simple.JdbcClient jdbcClient) {
        return new com.yumi.finance.cash.infrastructure.CashQueryImpl(jdbcClient);
    }

    @Bean(name = "costReportingFeed")
    public com.yumi.finance.costledger.api.CostReportingFeed costReportingFeed(
            org.springframework.jdbc.core.simple.JdbcClient jdbcClient) {
        return new com.yumi.finance.costledger.infrastructure.CostReportingFeedImpl(jdbcClient);
    }

    @Bean(name = "costLedgerQuery")
    public com.yumi.finance.costledger.api.CostLedgerQuery costLedgerQuery(
            org.springframework.jdbc.core.simple.JdbcClient jdbcClient) {
        return new com.yumi.finance.costledger.infrastructure.CostLedgerQueryImpl(jdbcClient);
    }

    @Bean(name = "operatingExpenseService")
    public com.yumi.finance.costledger.api.OperatingExpenseService operatingExpenseService(
            org.springframework.jdbc.core.simple.JdbcClient jdbcClient) {
        return new com.yumi.finance.costledger.application.OperatingExpenseServiceImpl(jdbcClient);
    }

    @Bean(name = "payrollPaymentParticipant")
    public com.yumi.finance.payroll.api.PayrollPaymentParticipant payrollPaymentParticipant(
            com.yumi.finance.payroll.application.PayrollRepository payrollRepository,
            CurrentActorProvider currentActorProvider,
            Clock clock) {
        return new com.yumi.finance.payroll.application.PayrollPaymentServiceImpl(
                payrollRepository, currentActorProvider, clock);
    }

    @Bean(name = "payrollReportingFeed")
    public com.yumi.finance.payroll.api.PayrollReportingFeed payrollReportingFeed(
            com.yumi.finance.payroll.application.PayrollRepository payrollRepository) {
        return new com.yumi.finance.payroll.application.PayrollReportingFeedImpl(payrollRepository);
    }

    @Bean(name = "payrollReviewService")
    public com.yumi.finance.payroll.api.PayrollReviewService payrollReviewService(
            com.yumi.finance.payroll.application.PayrollApplicationServiceImpl payrollApplication,
            com.yumi.finance.payroll.application.PayrollRepository payrollRepository,
            IdempotentCommandService idempotency,
            AuthorizationService authorization,
            PlatformTransactionManager transactionManager) {
        return new com.yumi.finance.payroll.application.PayrollReviewServiceImpl(
                payrollApplication, payrollRepository, idempotency, authorization,
                transactionManager);
    }

    @Bean(name = "payrollAdjustmentService")
    public com.yumi.finance.payroll.api.PayrollAdjustmentService payrollAdjustmentService(
            com.yumi.finance.payroll.application.PayrollRepository payrollRepository,
            IdempotentCommandService idempotency,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new com.yumi.finance.payroll.application.PayrollAdjustmentServiceImpl(
                payrollRepository, idempotency, authorization, currentActorProvider, clock,
                transactionManager);
    }

    @Bean(name = "payrollConfirmationService")
    public com.yumi.finance.payroll.api.PayrollConfirmationService payrollConfirmationService(
            com.yumi.finance.payroll.application.PayrollRepository payrollRepository,
            IdempotentCommandService idempotency,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            com.yumi.platform.api.audit.AuditService audit,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new com.yumi.finance.payroll.application.PayrollConfirmationServiceImpl(
                payrollRepository, idempotency, authorization, currentActorProvider, audit, clock,
                transactionManager);
    }

    @Bean(name = {"payrollGlueAllowancePolicyService", "payrollGlueAllowanceQuery"})
    public com.yumi.finance.payroll.application.PayrollGlueAllowanceServiceImpl payrollGlueAllowanceService(
            com.yumi.finance.payroll.application.PayrollGlueRepository payrollGlueRepository,
            IdempotentCommandService idempotency,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new com.yumi.finance.payroll.application.PayrollGlueAllowanceServiceImpl(
                payrollGlueRepository, idempotency, authorization, currentActorProvider, clock,
                transactionManager);
    }

    @Bean(name = "payrollCostSourceFeed")
    public com.yumi.finance.payroll.api.PayrollCostSourceFeed payrollCostSourceFeed(
            com.yumi.finance.payroll.application.PayrollRepository payrollRepository) {
        return new com.yumi.finance.payroll.application.PayrollCostSourceFeedImpl(payrollRepository);
    }

    @Bean(name = {"payrollApplicationService", "payrollQuery"})
    public com.yumi.finance.payroll.application.PayrollApplicationServiceImpl payrollApplicationService(
            com.yumi.finance.payroll.application.PayrollRepository payrollRepository,
            PayrollSourceRepository payrollSourceRepository,
            com.yumi.production.api.ProductionPayrollMaterialSourceFeed payrollMaterialFeed,
            com.yumi.workforce.api.WorkforceEmployeeDirectory workforceEmployeeDirectory,
            com.yumi.finance.payroll.api.PayrollCostSourceFeed payrollCostFeed,
            com.yumi.orders.aftersales.api.OrderAfterSalesCostSourceFeed afterSalesCostFeed,
            IdempotentCommandService idempotency,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new com.yumi.finance.payroll.application.PayrollApplicationServiceImpl(
                payrollRepository, payrollSourceRepository, payrollMaterialFeed,
                workforceEmployeeDirectory, idempotency,
                authorization, currentActorProvider, objectMapper, clock, transactionManager);
    }

    @Bean(name = "payrollSourceIngestionService")
    public PayrollSourceIngestionService payrollSourceIngestionService(
            PayrollSourceRepository repository,
            ProductionPayrollSourceFeed productionFeed,
            IdempotentCommandService idempotency,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new PayrollSourceIngestionServiceImpl(
                repository, productionFeed, idempotency, authorization, currentActorProvider,
                clock, transactionManager);
    }
}

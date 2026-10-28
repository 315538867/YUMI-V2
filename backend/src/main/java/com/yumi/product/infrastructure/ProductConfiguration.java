package com.yumi.product.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.api.audit.AuditService;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.numbering.BusinessNumberService;
import com.yumi.product.api.ProductApplicationService;
import com.yumi.product.api.ProductCostBasisQuery;
import com.yumi.product.api.ProductEntryConfigurationService;
import com.yumi.product.api.ProductQuery;
import com.yumi.product.api.ProductSchemeApplicationService;
import com.yumi.product.api.ProductSnapshotService;
import com.yumi.product.application.ProductBudgetMapper;
import com.yumi.product.application.ProductCommandService;
import com.yumi.product.application.ProductCostBasisQueryImpl;
import com.yumi.product.application.ProductEntryConfigurationServiceImpl;
import com.yumi.product.application.ProductSchemeCommandService;
import com.yumi.product.application.ProductSnapshotServiceImpl;
import com.yumi.product.application.port.BudgetRepository;
import com.yumi.product.application.port.ProductEntryConfigurationRepository;
import com.yumi.product.application.port.ProductRepository;
import com.yumi.product.application.port.ProductSnapshotRepository;
import com.yumi.product.application.port.SchemeRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 商品模块装配。
 *
 * <p>对外只注册 {@code product.api} 的公开契约。实现类不注册为独立 Bean：
 * 公开接口按类型唯一，避免调用方按类型注入时出现多实例歧义。
 */
@Configuration
public class ProductConfiguration {
    @Bean(name = "productReportingFeed")
    public com.yumi.product.api.ProductReportingFeed productReportingFeed(org.springframework.jdbc.core.simple.JdbcClient jdbcClient) {
        return new com.yumi.product.infrastructure.ProductReportingFeedImpl(jdbcClient);
    }


    @Bean
    public ProductBudgetMapper productBudgetMapper() {
        return ProductBudgetMapper.defaults();
    }

    @Bean
    @Primary
    public ProductApplicationService productApplicationService(
            ProductRepository products,
            SchemeRepository schemes,
            BudgetRepository budgets,
            ProductEntryConfigurationRepository configurations,
            BusinessNumberService numbering,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            AuditService audit,
            ProductBudgetMapper productBudgetMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new ProductCommandService(
                products, schemes, budgets, configurations, numbering, authorization,
                currentActorProvider, audit, productBudgetMapper, clock, transactionManager);
    }

    @Bean(name = "productQuery")
    public ProductQuery productQuery(ProductApplicationService productApplicationService) {
        return (ProductQuery) productApplicationService;
    }

    @Bean
    public ProductSchemeApplicationService productSchemeApplicationService(
            SchemeRepository schemes,
            BudgetRepository budgets,
            ProductApplicationService productApplicationService,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            AuditService audit,
            ProductBudgetMapper productBudgetMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new ProductSchemeCommandService(
                schemes, budgets, (ProductCommandService) productApplicationService, authorization,
                currentActorProvider, audit, productBudgetMapper, clock, transactionManager);
    }

    @Bean
    public ProductEntryConfigurationService productEntryConfigurationService(
            ProductEntryConfigurationRepository repository,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            AuditService audit,
            Clock clock) {
        return new ProductEntryConfigurationServiceImpl(
                repository, authorization, currentActorProvider, audit, clock);
    }

    @Bean
    public ProductSnapshotService productSnapshotService(
            ProductSnapshotRepository snapshots,
            ProductApplicationService productApplicationService,
            ProductSchemeApplicationService productSchemeApplicationService,
            AuthorizationService authorization,
            ObjectMapper objectMapper,
            Clock clock) {
        return new ProductSnapshotServiceImpl(
                snapshots,
                (ProductCommandService) productApplicationService,
                (ProductSchemeCommandService) productSchemeApplicationService,
                authorization,
                objectMapper,
                clock);
    }

    @Bean
    public ProductCostBasisQuery productCostBasisQuery(
            ProductSnapshotService productSnapshotService, AuthorizationService authorization) {
        return new ProductCostBasisQueryImpl(productSnapshotService, authorization);
    }

    @Bean(name = "productOrderEntryQuery")
    public com.yumi.product.api.ProductOrderEntryQuery productOrderEntryQuery(
            ProductSnapshotService productSnapshotService, AuthorizationService authorization) {
        return new com.yumi.product.application.ProductOrderEntryQueryImpl(
                productSnapshotService, authorization);
    }
}

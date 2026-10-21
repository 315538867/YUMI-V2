package com.yumi.orders.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.customer.api.CustomerSnapshotService;
import com.yumi.orders.api.OrderApplicationService;
import com.yumi.orders.api.OrderQuery;
import com.yumi.orders.application.OrderApplicationServiceImpl;
import com.yumi.orders.application.port.OrderRepository;
import com.yumi.platform.api.audit.AuditService;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.idempotency.IdempotentCommandService;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.numbering.BusinessNumberService;
import com.yumi.product.api.ProductCostBasisQuery;
import com.yumi.product.api.ProductSnapshotService;
import com.yumi.production.api.ProductionConsumptionParticipant;
import com.yumi.production.api.ProductionDemandParticipant;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

/** 订单模块装配：只注册 {@code orders.api} 公开契约。 */
@Configuration
public class OrdersConfiguration {

    @Bean(name = "orderBeans")
    public OrderBeans orderBeans(
            OrderRepository orderRepository,
            CustomerSnapshotService customerSnapshots,
            ProductSnapshotService productSnapshots,
            ProductCostBasisQuery productCostBasisQuery,
            ProductionDemandParticipant productionDemandParticipant,
            IdempotentCommandService idempotentCommandService,
            BusinessNumberService numbering,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            AuditService audit,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new OrderBeans(new OrderApplicationServiceImpl(
                orderRepository, customerSnapshots, productSnapshots, productCostBasisQuery,
                productionDemandParticipant, idempotentCommandService, numbering, authorization,
                currentActorProvider, audit, objectMapper, clock, transactionManager));
    }

    @Bean(name = "orderChangeService")
    public com.yumi.orders.api.OrderChangeService orderChangeService(
            OrderRepository orderRepository,
            ProductionDemandParticipant productionDemandParticipant,
            com.yumi.production.api.ProductionTaskAdjustmentParticipant taskAdjustments,
            ProductSnapshotService productSnapshots,
            ProductCostBasisQuery productCostBasisQuery,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new com.yumi.orders.application.OrderChangeServiceImpl(
                orderRepository, productionDemandParticipant, taskAdjustments, productSnapshots,
                productCostBasisQuery, authorization, currentActorProvider, objectMapper, clock,
                transactionManager);
    }

    @Bean(name = "orderDeliveryService")
    public com.yumi.orders.api.OrderDeliveryService orderDeliveryService(
            OrderRepository orderRepository,
            ProductionConsumptionParticipant productionConsumptionParticipant,
            IdempotentCommandService idempotentCommandService,
            BusinessNumberService numbering,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            AuditService audit,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new com.yumi.orders.fulfillment.application.OrderDeliveryServiceImpl(
                orderRepository, productionConsumptionParticipant, idempotentCommandService,
                numbering, authorization, currentActorProvider, audit, clock, transactionManager);
    }

    @Bean(name = "orderPaymentParticipant")
    public com.yumi.orders.api.OrderPaymentParticipant orderPaymentParticipant(
            OrderRepository orderRepository,
            CurrentActorProvider currentActorProvider,
            Clock clock) {
        return new com.yumi.orders.receivable.application.OrderPaymentParticipantImpl(
                orderRepository, currentActorProvider, clock);
    }

    @Bean(name = "orderCompletionService")
    public com.yumi.orders.api.OrderCompletionService orderCompletionService(
            OrderRepository orderRepository,
            IdempotentCommandService idempotentCommandService,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            AuditService audit,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new com.yumi.orders.fulfillment.application.OrderCompletionServiceImpl(
                orderRepository, idempotentCommandService, authorization, currentActorProvider,
                audit, clock, transactionManager);
    }

    @Bean(name = "openingOrderService")
    public com.yumi.orders.api.OpeningOrderService openingOrderService(
            OrderRepository orderRepository,
            CustomerSnapshotService customerSnapshots,
            ProductSnapshotService productSnapshots,
            ProductCostBasisQuery costBasis,
            ProductionDemandParticipant productionDemandParticipant,
            com.yumi.production.api.ProductionOpeningApplicationService productionOpening,
            IdempotentCommandService idempotentCommandService,
            BusinessNumberService numbering,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            AuditService audit,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new com.yumi.orders.trade.application.OpeningOrderServiceImpl(
                orderRepository, customerSnapshots, productSnapshots, costBasis,
                productionDemandParticipant, productionOpening, idempotentCommandService, numbering,
                authorization, currentActorProvider, audit, objectMapper, clock, transactionManager);
    }

    @Bean(name = "orderReportingFeed")
    public com.yumi.orders.api.OrderReportingFeed orderReportingFeed(
            OrderRepository orderRepository) {
        return new com.yumi.orders.trade.application.OrderReportingFeedImpl(orderRepository);
    }

    @Bean(name = "afterSalesReturnDispositionService")
    public com.yumi.orders.aftersales.api.AfterSalesReturnDispositionService afterSalesReturnDispositionService(
            com.yumi.orders.aftersales.application.port.AfterSalesRepository afterSalesRepository,
            com.yumi.production.api.ProductionAfterSalesReturnParticipant productionReturns,
            IdempotentCommandService idempotentCommandService,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            AuditService audit,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new com.yumi.orders.aftersales.application.AfterSalesReturnDispositionServiceImpl(
                afterSalesRepository, productionReturns, idempotentCommandService, authorization,
                currentActorProvider, audit, clock, transactionManager);
    }

    @Bean(name = {"afterSalesApplicationService", "afterSalesQuery"})
    public com.yumi.orders.aftersales.application.AfterSalesCaseServiceImpl afterSalesService(
            com.yumi.orders.aftersales.application.port.AfterSalesRepository afterSalesRepository,
            com.yumi.orders.aftersales.api.AfterSalesReturnDispositionService afterSalesDispositions,
            OrderRepository orderRepository,
            IdempotentCommandService idempotentCommandService,
            BusinessNumberService numbering,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            AuditService audit,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new com.yumi.orders.aftersales.application.AfterSalesCaseServiceImpl(
                afterSalesRepository, afterSalesDispositions, orderRepository, idempotentCommandService,
                numbering, authorization, currentActorProvider, audit, clock, transactionManager);
    }

    @Bean(name = "afterSalesReturnService")
    public com.yumi.orders.aftersales.api.AfterSalesReturnService afterSalesReturnService(
            com.yumi.orders.aftersales.application.port.AfterSalesRepository afterSalesRepository,
            com.yumi.production.api.ProductionAfterSalesReturnParticipant productionReturns,
            IdempotentCommandService idempotentCommandService,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            AuditService audit,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new com.yumi.orders.aftersales.application.AfterSalesReturnServiceImpl(
                afterSalesRepository, productionReturns, idempotentCommandService, authorization,
                currentActorProvider, audit, objectMapper, clock, transactionManager);
    }

    @Bean(name = "afterSalesRouteService")
    public com.yumi.orders.aftersales.api.AfterSalesRouteService afterSalesRouteService(
            com.yumi.orders.aftersales.application.port.AfterSalesRepository afterSalesRepository,
            com.yumi.production.api.ProductionAfterSalesDemandParticipant productionAfterSalesDemands,
            IdempotentCommandService idempotentCommandService,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            AuditService audit,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new com.yumi.orders.aftersales.application.AfterSalesRouteServiceImpl(
                afterSalesRepository, productionAfterSalesDemands, idempotentCommandService,
                authorization, currentActorProvider, audit, objectMapper, clock, transactionManager);
    }

    @Bean(name = "afterSalesPaymentParticipant")
    public com.yumi.orders.aftersales.api.AfterSalesPaymentParticipant afterSalesPaymentParticipant(
            com.yumi.orders.aftersales.application.port.AfterSalesRepository afterSalesRepository,
            CurrentActorProvider currentActorProvider,
            Clock clock) {
        return new com.yumi.orders.aftersales.application.AfterSalesPaymentParticipantImpl(
                afterSalesRepository, currentActorProvider, clock);
    }

    @Bean(name = "afterSalesObligationChangeService")
    public com.yumi.orders.aftersales.api.AfterSalesObligationChangeService afterSalesObligationChangeService(
            com.yumi.orders.aftersales.application.port.AfterSalesRepository afterSalesRepository,
            com.yumi.production.api.ProductionAfterSalesDemandParticipant productionAfterSalesDemands,
            com.yumi.production.api.ProductionAfterSalesReturnParticipant productionReturns,
            IdempotentCommandService idempotentCommandService,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            AuditService audit,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new com.yumi.orders.aftersales.application.AfterSalesObligationChangeServiceImpl(
                afterSalesRepository, productionAfterSalesDemands, productionReturns,
                idempotentCommandService, authorization, currentActorProvider, audit,
                objectMapper, clock, transactionManager);
    }

    @Bean(name = {"afterSalesCostService", "orderAfterSalesCostSourceFeed"})
    public com.yumi.orders.aftersales.application.AfterSalesCostServiceImpl afterSalesCostService(
            com.yumi.orders.aftersales.application.port.AfterSalesRepository afterSalesRepository,
            IdempotentCommandService idempotentCommandService,
            AuthorizationService authorization,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new com.yumi.orders.aftersales.application.AfterSalesCostServiceImpl(
                afterSalesRepository, idempotentCommandService, authorization, clock,
                transactionManager);
    }

    @Bean(name = "afterSalesReportingFeed")
    public com.yumi.orders.aftersales.api.AfterSalesReportingFeed afterSalesReportingFeed(
            com.yumi.orders.aftersales.application.port.AfterSalesRepository afterSalesRepository) {
        return new com.yumi.orders.aftersales.application.AfterSalesReportingFeedImpl(
                afterSalesRepository);
    }

    @Bean(name = "orderApplicationService")
    @Primary
    public OrderApplicationService orderApplicationService(OrderBeans beans) {
        return beans.service();
    }

    @Bean(name = "orderQuery")
    public OrderQuery orderQuery(OrderBeans beans) {
        return beans.service();
    }

    /** 订单实现持有者（仅供装配）。 */
    public record OrderBeans(OrderApplicationServiceImpl service) {
    }
}

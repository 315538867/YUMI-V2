package com.yumi.production.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.numbering.BusinessNumberService;
import com.yumi.product.api.ProductSnapshotService;
import com.yumi.production.api.ProductionDemandParticipant;
import com.yumi.production.api.TaskApplicationService;
import com.yumi.production.api.TaskVerificationService;
import com.yumi.production.application.DailyLoadService;
import com.yumi.production.application.NewProductCapacityService;
import com.yumi.production.application.ProductionDemandServiceImpl;
import com.yumi.production.application.DefectReasonAdministrationServiceImpl;
import com.yumi.production.application.ProductionConsumptionServiceImpl;
import com.yumi.production.application.ProductionFactCorrectionService;
import com.yumi.production.application.ProductionOfflineInventoryTransferServiceImpl;
import com.yumi.production.application.ProductionTraceServiceImpl;
import com.yumi.production.application.DispositionServiceImpl;
import com.yumi.production.application.ResponsibilityServiceImpl;
import com.yumi.production.application.TaskArrangementService;
import com.yumi.production.application.TaskVerificationServiceImpl;
import com.yumi.production.application.WorkloadAssessor;
import com.yumi.production.application.port.AssessmentRepository;
import com.yumi.production.application.port.DailyLoadRepository;
import com.yumi.production.application.port.ConsumptionRepository;
import com.yumi.production.application.port.CorrectionRepository;
import com.yumi.production.application.port.DispositionRepository;
import com.yumi.production.application.port.TraceRepository;
import com.yumi.production.application.port.OtherTaskRepository;
import com.yumi.production.application.port.ProductionRepository;
import com.yumi.production.application.port.SourceReservationRepository;
import com.yumi.production.application.port.TaskRepository;
import com.yumi.production.application.port.VerificationRepository;
import com.yumi.workforce.api.AssignmentSubjectSnapshotService;
import com.yumi.workforce.api.WorkforceActivityReservationParticipant;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 生产模块装配。
 *
 * <p>平台模块不反向依赖业务模块；每个业务模块在自己的基础设施层装配，
 * 只把 {@code production.api} 的公开接口注册为 Bean。
 */
@Configuration
public class ProductionConfiguration {

    @Bean(name = "productionDemandParticipant")
    public ProductionDemandParticipant productionDemandParticipant(
            ProductionRepository productionRepository,
            com.yumi.production.application.port.ReturnedProductRepository returnedProductRepository,
            AuthorizationService authorization,
            ObjectMapper objectMapper,
            Clock clock) {
        return new ProductionDemandServiceImpl(
                productionRepository, returnedProductRepository, authorization, objectMapper, clock);
    }

    @Bean(name = "productionPayrollSourceFeed")
    public com.yumi.production.api.ProductionPayrollSourceFeed productionPayrollSourceFeed(
            org.springframework.jdbc.core.simple.JdbcClient jdbcClient,
            ObjectMapper objectMapper) {
        return new com.yumi.production.application.ProductionPayrollSourceFeedImpl(
                jdbcClient, objectMapper);
    }

    @Bean(name = "productionPayrollMaterialSourceFeed")
    public com.yumi.production.api.ProductionPayrollMaterialSourceFeed productionPayrollMaterialSourceFeed(
            org.springframework.jdbc.core.simple.JdbcClient jdbcClient,
            ObjectMapper objectMapper) {
        return new com.yumi.production.application.ProductionPayrollMaterialSourceFeedImpl(
                jdbcClient, objectMapper);
    }

    @Bean(name = "productionAfterSalesDemandParticipant")
    public com.yumi.production.api.ProductionAfterSalesDemandParticipant productionAfterSalesDemandParticipant(
            ProductionRepository productionRepository,
            com.yumi.production.application.port.ReturnedProductRepository returnedProductRepository,
            ProductionDemandParticipant productionDemandParticipant,
            ObjectMapper objectMapper) {
        return new com.yumi.production.application.ProductionAfterSalesDemandServiceImpl(
                productionRepository, returnedProductRepository, productionDemandParticipant,
                objectMapper);
    }

    @Bean(name = "productionAfterSalesReturnParticipant")
    public com.yumi.production.api.ProductionAfterSalesReturnParticipant productionAfterSalesReturnParticipant(
            com.yumi.production.application.port.ReturnedProductRepository returnedProductRepository,
            ProductionRepository productionRepository,
            VerificationRepository verificationRepository,
            com.yumi.production.application.port.DispositionRepository dispositionRepository,
            CurrentActorProvider currentActorProvider,
            Clock clock) {
        return new com.yumi.production.application.ProductionAfterSalesReturnServiceImpl(
                returnedProductRepository, productionRepository, verificationRepository,
                dispositionRepository, currentActorProvider, clock);
    }

    @Bean(name = "productionOpeningApplicationService")
    public com.yumi.production.api.ProductionOpeningApplicationService productionOpeningApplicationService(
            ProductionRepository productionRepository,
            CurrentActorProvider currentActorProvider,
            Clock clock) {
        return new com.yumi.production.application.ProductionOpeningServiceImpl(
                productionRepository, currentActorProvider, clock);
    }

    @Bean
    public DailyLoadService dailyLoadService(DailyLoadRepository dailyLoads) {
        return new DailyLoadService(dailyLoads);
    }

    @Bean
    public NewProductCapacityService newProductCapacityService(
            DailyLoadRepository dailyLoads, ProductSnapshotService productSnapshots) {
        return new NewProductCapacityService(dailyLoads, productSnapshots);
    }

    @Bean(name = "taskVerificationService")
    public TaskVerificationService taskVerificationService(
            TaskRepository taskRepository,
            ProductionRepository productionRepository,
            SourceReservationRepository sourceReservations,
            com.yumi.production.application.port.RepairSourceRepository repairSourceRepository,
            VerificationRepository verifications,
            OtherTaskRepository otherTasks,
            AssessmentRepository assessments,
            DailyLoadService dailyLoadService,
            NewProductCapacityService newProductCapacityService,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new TaskVerificationServiceImpl(
                taskRepository, productionRepository, sourceReservations, repairSourceRepository,
                verifications, otherTasks,
                assessments, dailyLoadService, newProductCapacityService, authorization,
                currentActorProvider, objectMapper, clock, transactionManager);
    }

    /**
     * 缺陷原因维护与查询共享同一实现；只注册公开契约，避免按实现类型注入歧义。
     */
    @Bean(name = "defectReasonBeans")
    public DefectReasonBeans defectReasonBeans(
            DispositionRepository dispositionRepository, AuthorizationService authorization) {
        return new DefectReasonBeans(
                new DefectReasonAdministrationServiceImpl(dispositionRepository, authorization));
    }

    @Bean(name = "defectReasonAdministration")
    @org.springframework.context.annotation.Primary
    public com.yumi.production.api.DefectReasonAdministrationService defectReasonAdministration(
            DefectReasonBeans beans) {
        return beans.service();
    }

    @Bean(name = "defectReasonQuery")
    public com.yumi.production.api.DefectReasonQuery defectReasonQuery(DefectReasonBeans beans) {
        return beans.service();
    }

    @Bean(name = "responsibilityApplicationService")
    public com.yumi.production.api.ResponsibilityApplicationService responsibilityApplicationService(
            DispositionRepository dispositionRepository,
            AuthorizationService authorization,
            PlatformTransactionManager transactionManager) {
        return new ResponsibilityServiceImpl(dispositionRepository, authorization, transactionManager);
    }

    @Bean(name = "dispositionBeans")
    public DispositionBeans dispositionBeans(
            DispositionRepository dispositionRepository,
            AuthorizationService authorization,
            PlatformTransactionManager transactionManager) {
        return new DispositionBeans(
                new DispositionServiceImpl(dispositionRepository, authorization, transactionManager));
    }

    @Bean(name = "dispositionApplicationService")
    @org.springframework.context.annotation.Primary
    public com.yumi.production.api.DispositionApplicationService dispositionApplicationService(
            DispositionBeans beans) {
        return beans.service();
    }

    @Bean(name = "dispositionQuery")
    public com.yumi.production.api.DispositionQuery dispositionQuery(DispositionBeans beans) {
        return beans.service();
    }

    /** 缺陷原因实现持有者（仅供装配，不对外暴露实现类型）。 */
    public record DefectReasonBeans(DefectReasonAdministrationServiceImpl service) {
    }

    /** 处置实现持有者。 */
    public record DispositionBeans(DispositionServiceImpl service) {
    }

    @Bean(name = "productionTaskAdjustmentParticipant")
    public com.yumi.production.api.ProductionTaskAdjustmentParticipant
            productionTaskAdjustmentParticipant(
                    com.yumi.production.application.port.ReductionRepository reductionRepository,
                    TaskRepository taskRepository,
                    DailyLoadService dailyLoadService,
                    NewProductCapacityService newProductCapacityService,
                    AuthorizationService authorization) {
        return new com.yumi.production.application.ProductionTaskAdjustmentServiceImpl(
                reductionRepository, taskRepository, dailyLoadService, newProductCapacityService,
                authorization);
    }

    @Bean(name = "productionConsumptionParticipant")
    public com.yumi.production.api.ProductionConsumptionParticipant productionConsumptionParticipant(
            ProductionRepository productionRepository,
            ConsumptionRepository consumptionRepository,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            Clock clock) {
        return new ProductionConsumptionServiceImpl(
                productionRepository, consumptionRepository, authorization, currentActorProvider, clock);
    }

    @Bean(name = "transferBeans")
    public TransferBeans transferBeans(
            ProductionRepository productionRepository,
            ConsumptionRepository consumptionRepository,
            VerificationRepository verifications,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            Clock clock) {
        return new TransferBeans(new ProductionOfflineInventoryTransferServiceImpl(
                productionRepository, consumptionRepository, verifications, authorization,
                currentActorProvider, clock));
    }

    @Bean(name = "productionOfflineInventoryTransferParticipant")
    public com.yumi.production.api.ProductionOfflineInventoryTransferParticipant
            productionOfflineInventoryTransferParticipant(TransferBeans beans) {
        return beans.service();
    }

    /** 线下移交实现持有者（测试需要直接调用提交与不可更正校验）。 */
    public record TransferBeans(ProductionOfflineInventoryTransferServiceImpl service) {
    }

    @Bean(name = "traceBeans")
    public TraceBeans traceBeans(
            TraceRepository traceRepository,
            ProductionRepository productionRepository,
            AuthorizationService authorization) {
        return new TraceBeans(new ProductionTraceServiceImpl(
                traceRepository, productionRepository, authorization));
    }

    @Bean(name = "productionQuery")
    @org.springframework.context.annotation.Primary
    public com.yumi.production.api.ProductionQuery productionQuery(TraceBeans beans) {
        return beans.service();
    }

    @Bean(name = "productionReportingFeed")
    public com.yumi.production.api.ProductionReportingFeed productionReportingFeed(TraceBeans beans) {
        return beans.service();
    }

    /** 追溯查询实现持有者（仅供装配）。 */
    public record TraceBeans(ProductionTraceServiceImpl service) {
    }

    @Bean(name = "correctionBeans")
    public CorrectionBeans correctionBeans(
            TaskRepository taskRepository,
            ProductionRepository productionRepository,
            VerificationRepository verifications,
            CorrectionRepository correctionRepository,
            DailyLoadService dailyLoadService,
            NewProductCapacityService newProductCapacityService,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new CorrectionBeans(new ProductionFactCorrectionService(
                taskRepository, productionRepository, verifications, correctionRepository,
                dailyLoadService, newProductCapacityService, authorization, currentActorProvider,
                objectMapper, clock, transactionManager));
    }

    @Bean(name = "productionCorrectionService")
    @org.springframework.context.annotation.Primary
    public com.yumi.production.api.ProductionCorrectionService productionCorrectionService(
            CorrectionBeans beans) {
        return beans.service();
    }

    @Bean(name = "productionCorrectionQuery")
    public com.yumi.production.api.ProductionCorrectionQuery productionCorrectionQuery(
            CorrectionBeans beans) {
        return beans.service();
    }

    /** 更正实现持有者（仅供装配）。 */
    public record CorrectionBeans(ProductionFactCorrectionService service) {
    }

    @Bean
    public WorkloadAssessor workloadAssessor() {
        return WorkloadAssessor.defaults();
    }

    @Bean(name = "taskArrangementService")
    public TaskApplicationService taskArrangementService(
            TaskRepository taskRepository,
            ProductionRepository productionRepository,
            SourceReservationRepository sourceReservations,
            com.yumi.production.application.port.RepairSourceRepository repairSourceRepository,
            com.yumi.production.application.port.ReductionRepository reductionRepository,
            OtherTaskRepository otherTasks,
            AssessmentRepository assessments,
            DailyLoadRepository dailyLoads,
            DailyLoadService dailyLoadService,
            NewProductCapacityService newProductCapacityService,
            ProductSnapshotService productSnapshots,
            AssignmentSubjectSnapshotService assignmentSubjectSnapshots,
            WorkforceActivityReservationParticipant workforceReservations,
            WorkloadAssessor workloadAssessor,
            BusinessNumberService numbering,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new TaskArrangementService(
                taskRepository, productionRepository, sourceReservations, repairSourceRepository,
                reductionRepository, otherTasks, assessments,
                dailyLoads, dailyLoadService, newProductCapacityService,
                productSnapshots, assignmentSubjectSnapshots, workforceReservations,
                workloadAssessor, numbering, authorization, currentActorProvider, objectMapper, clock,
                transactionManager);
    }
}

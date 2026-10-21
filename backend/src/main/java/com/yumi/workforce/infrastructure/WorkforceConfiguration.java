package com.yumi.workforce.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.api.audit.AuditService;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.numbering.BusinessNumberService;
import com.yumi.workforce.api.AssignmentSubjectQuery;
import com.yumi.workforce.api.AssignmentSubjectSnapshotService;
import com.yumi.workforce.api.WorkforceActivityReservationParticipant;
import com.yumi.workforce.api.WorkforceApplicationService;
import com.yumi.workforce.api.WorkforceStandardQuery;
import com.yumi.workforce.application.WorkforceActivityReservationServiceImpl;
import com.yumi.workforce.application.WorkforceCommandService;
import com.yumi.workforce.application.WorkforceSnapshotServiceImpl;
import com.yumi.workforce.application.port.ActivityReservationRepository;
import com.yumi.workforce.application.port.WorkforceRepository;
import com.yumi.workforce.application.port.WorkforceSnapshotRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 人员与小组模块装配。
 *
 * <p>对外只注册 {@code workforce.api} 的公开接口；实现类保留在本模块内部。
 */
@Configuration
public class WorkforceConfiguration {
    @Bean(name = "workforceReportingFeed")
    public com.yumi.workforce.api.WorkforceReportingFeed workforceReportingFeed(org.springframework.jdbc.core.simple.JdbcClient jdbcClient) {
        return new com.yumi.workforce.infrastructure.WorkforceReportingFeedImpl(jdbcClient);
    }


    private WorkforceCommandService newWorkforceCommandService(
            WorkforceRepository workforceRepository,
            ActivityReservationRepository activityReservations,
            BusinessNumberService numbering,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            AuditService audit,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new WorkforceCommandService(
                workforceRepository, activityReservations, numbering, authorization,
                currentActorProvider, audit, clock, transactionManager);
    }

    /**
     * 公开命令契约的默认实现。
     *
     * <p>应用服务同时实现多个公开接口；这里用 {@link Primary} 标记命令契约，避免调用方按类型注入时
     * 出现多实例歧义。其他契约（标准、主体资格）通过各自 Bean 名称注入。
     */
    /** 公开契约装配：三个查询/命令契约共享同一应用服务实例（不含内部类型暴露）。 */
    @Bean
    public WorkforceBeans workforceBeans(
            WorkforceRepository workforceRepository,
            ActivityReservationRepository activityReservations,
            BusinessNumberService numbering,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            AuditService audit,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        WorkforceCommandService commandService = newWorkforceCommandService(
                workforceRepository, activityReservations, numbering, authorization,
                currentActorProvider, audit, clock, transactionManager);
        return new WorkforceBeans(commandService);
    }

    /**
     * 公开命令契约。
     *
     * <p>应用服务同时实现多个公开接口；这里用 {@link Primary} 标记“命令”契约，
     * 其余契约（标准查询、主体资格）按 Bean 名称注入。
     */
    @Bean
    @Primary
    public WorkforceApplicationService workforceApplicationService(WorkforceBeans beans) {
        return beans.commands();
    }

    @Bean(name = "workforceStandardQuery")
    public WorkforceStandardQuery workforceStandardQuery(WorkforceBeans beans) {
        return beans.commands();
    }

    @Bean(name = "assignmentSubjectQuery")
    public AssignmentSubjectQuery assignmentSubjectQuery(WorkforceBeans beans) {
        return beans.commands();
    }

    @Bean(name = "assignmentSubjectSnapshotService")
    public AssignmentSubjectSnapshotService assignmentSubjectSnapshotService(
            WorkforceBeans beans,
            WorkforceSnapshotRepository workforceSnapshots,
            WorkforceRepository workforceRepository,
            AuthorizationService authorization,
            ObjectMapper objectMapper,
            Clock clock) {
        return new WorkforceSnapshotServiceImpl(
                workforceSnapshots, workforceRepository, beans.commands(), beans.commands(),
                authorization, objectMapper, clock);
    }

    @Bean(name = "workforceActivityReservationParticipant")
    public WorkforceActivityReservationParticipant workforceActivityReservationParticipant(
            ActivityReservationRepository activityReservations,
            AssignmentSubjectSnapshotService assignmentSubjectSnapshotService,
            Clock clock) {
        return new WorkforceActivityReservationServiceImpl(
                activityReservations,
                (WorkforceSnapshotServiceImpl) assignmentSubjectSnapshotService,
                clock);
    }

    /**
     * 人员应用服务持有的公开契约集合。
     *
     * <p>实现类本身保留在模块内部；装配只输出公开接口，便于其他模块按契约注入。
     */
    public record WorkforceBeans(WorkforceCommandService commands) {
    }
}

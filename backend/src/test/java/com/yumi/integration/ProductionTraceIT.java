package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.platform.api.http.ApiException;
import com.yumi.production.api.ArrangeTaskCommand;
import com.yumi.production.api.ConsumeProductionCommand;
import com.yumi.production.api.DefectGroupRequest;
import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.ProductionConsumptionParticipant;
import com.yumi.production.api.ProductionDemandRef;
import com.yumi.production.api.ProductionLinkRef;
import com.yumi.production.api.ProductionProjectionRow;
import com.yumi.production.api.ProductionQuery;
import com.yumi.production.api.ProductionReportingFeed;
import com.yumi.production.api.ResponsibilityType;
import com.yumi.production.api.SubjectRef;
import com.yumi.production.api.SubjectType;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.api.TaskApplicationService;
import com.yumi.production.api.TaskItemResultRequest;
import com.yumi.production.api.TaskRef;
import com.yumi.production.api.TaskType;
import com.yumi.production.api.TaskVerificationService;
import com.yumi.production.api.VerifyTaskCommand;
import com.yumi.support.AbstractProductionFixtureTest;
import com.yumi.workforce.api.EmployeeRef;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 订单到实际事实的追溯与公开数量投影。
 *
 * <p>规则来源：`docs/一期/11-生产作业模块.md` §5.2.1、§7.7、§9.3。真实 MySQL 验证：
 * 读模型能核对两套数量账，不从计划数量虚构完成事实，来源/缺口/延迟可识别。
 */
class ProductionTraceIT extends AbstractProductionFixtureTest {

    /** 工作日期设为过去，使核验发生在之后，可识别为延迟核验。 */
    private static final LocalDate WORK_DATE = LocalDate.parse("2026-10-02");

    @Autowired
    private TaskApplicationService taskService;

    @Autowired
    private TaskVerificationService verificationService;

    @Autowired
    private ProductionConsumptionParticipant consumption;

    @Autowired
    private ProductionQuery productionQuery;

    @Autowired
    private ProductionReportingFeed reportingFeed;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("订单项追溯：两套数量账守恒，事实带来源与时间")
    void traceBalancesBothLedgers() {
        ProductionDemandRef demand = createNewProductDemand("ORD-TR-1", TargetProcess.MAKING, 100,
                createProduct("百件商品", 20, 10), 4);
        EmployeeRef employee = hireEmployee("张三", "MAKING");
        TaskRef task = asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION, new SubjectRef(SubjectType.EMPLOYEE, employee.employeeBusinessId()),
                WORK_DATE, TargetProcess.MAKING,
                List.of(new com.yumi.production.api.TaskItemRequest(
                        demand.productionSourceBusinessId(), 100)), null, null, null)));
        String itemId = asAdmin(() -> taskService.getTask(task.taskBusinessId()))
                .items().getFirst().taskItemBusinessId();

        asAdmin(() -> verificationService.verify(new VerifyTaskCommand(
                List.of(new TaskItemResultRequest(itemId, 2, 90, 5, 3, List.of(
                        new DefectGroupRequest("GLUE_OVERFLOW", null, 3, 0, List.of(
                                new DefectGroupRequest.ScrapPartRequest(
                                        3, PhysicalDestination.DESTROYED,
                                        ResponsibilityType.STUDIO, "STUDIO-1", null))),
                        new DefectGroupRequest("BUBBLE", null, 5, 5, List.of())), null)))));
        consume(demand, 90);

        var trace = asAdmin(() -> productionQuery.traceOfOrderItem("ORD-TR-1"));
        assertThat(trace.orderBusinessId()).isEqualTo("ORD-ORD-TR-1");
        assertThat(trace.orderNo()).isNotBlank();
        assertThat(trace.productNames()).isNotNull();
        assertThat(trace.demands()).hasSize(1);
        var demandTrace = trace.demands().getFirst();

        assertThat(demandTrace.ledger().demandBalanced()).isTrue();
        assertThat(demandTrace.ledger().physicalBalanced()).isTrue();
        assertThat(demandTrace.ledger().requiredQuantity()).isEqualTo(100);
        assertThat(demandTrace.ledger().pendingWorkQuantity()).as("未完成退回可安排").isEqualTo(2);
        assertThat(demandTrace.ledger().inProcessQuantity()).as("返工仍在制").isEqualTo(5);
        assertThat(demandTrace.ledger().fulfilledQuantity()).isEqualTo(90);
        assertThat(demandTrace.ledger().fulfillmentGapQuantity()).isEqualTo(3);
        assertThat(demandTrace.ledger().physical().cumulativeMakingCreatedQuantity()).isEqualTo(98);
        assertThat(demandTrace.ledger().physical().inSystemQuantity()
                + demandTrace.ledger().physical().consumedQuantity()
                + demandTrace.ledger().physical().destroyedQuantity()
                + demandTrace.ledger().physical().offlineInventoryQuantity())
                .isEqualTo(98);

        assertThat(demandTrace.tasks()).singleElement().satisfies(taskTrace -> {
            assertThat(taskTrace.workDate()).isEqualTo(WORK_DATE);
            assertThat(taskTrace.items()).singleElement().satisfies(item -> {
                assertThat(item.completedQuantity()).isEqualTo(98);
                assertThat(item.unfinishedQuantity()).isEqualTo(2);
                assertThat(item.verificationStatus()).isEqualTo("VERIFIED");
            });
        });
        assertThat(demandTrace.facts()).isNotEmpty();
        assertThat(demandTrace.facts()).allSatisfy(fact -> {
            assertThat(fact.productionSourceBusinessId()).isEqualTo(demand.productionSourceBusinessId());
            assertThat(fact.physicalRootSourceBusinessId()).isEqualTo(demand.physicalRootSourceBusinessId());
            assertThat(fact.occurredAt()).isNotNull();
        });
        assertThat(demandTrace.facts()).extracting(f -> f.factKind())
                .contains("EXECUTION", "DEFECT", "TERMINAL_EXIT", "CONSUMPTION");
        assertThat(demandTrace.gapReasons()).anySatisfy(reason ->
                assertThat(reason).contains("履约缺口 3"));

        var overview = asAdmin(() -> productionQuery.overview());
        assertThat(overview.requiredQuantity()).isEqualTo(100);
        assertThat(overview.scheduledQuantity()).isEqualTo(100);
        assertThat(overview.productionFactQuantity()).as("首个工序已处理 98 件").isEqualTo(98);
        assertThat(overview.fulfilledQuantity()).isEqualTo(90);
        assertThat(overview.remainingQuantity()).isEqualTo(10);
        assertThat(overview.processes().getFirst().process()).isEqualTo(TargetProcess.MAKING);
        assertThat(overview.processes().getFirst().scheduledQuantity()).isEqualTo(100);
        assertThat(overview.processes().getFirst().completedQuantity()).isEqualTo(90);
        assertThat(asAdmin(() -> productionQuery.recentTraces(0, 10)))
                .extracting(itemTrace -> itemTrace.orderItemBusinessId())
                .contains("ORD-TR-1");
    }

    @Test
    @DisplayName("计划数量不虚构完成数量：未核验任务只暴露计划与未核验原因")
    void plannedQuantityIsNotReportedAsCompleted() {
        ProductionDemandRef demand = createNewProductDemand("ORD-TR-2", TargetProcess.MAKING, 20,
                createProduct("计划商品", 20, 10), 4);
        EmployeeRef employee = hireEmployee("李四", "MAKING");
        asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION, new SubjectRef(SubjectType.EMPLOYEE, employee.employeeBusinessId()),
                WORK_DATE, TargetProcess.MAKING,
                List.of(new com.yumi.production.api.TaskItemRequest(
                        demand.productionSourceBusinessId(), 20)), null, null, null)));

        var trace = asAdmin(() -> productionQuery.traceOfOrderItem("ORD-TR-2"));
        var demandTrace = trace.demands().getFirst();
        assertThat(demandTrace.ledger().physical().cumulativeMakingCreatedQuantity())
                .as("未核验前不存在任何制作产物")
                .isZero();
        assertThat(demandTrace.ledger().physical().inSystemQuantity()).isZero();
        assertThat(demandTrace.ledger().fulfilledQuantity()).isZero();
        assertThat(demandTrace.tasks()).singleElement()
                .satisfies(task -> assertThat(task.items()).singleElement()
                        .satisfies(item -> {
                            assertThat(item.plannedQuantity()).isEqualTo(20);
                            assertThat(item.completedQuantity()).isZero();
                            assertThat(item.verificationStatus()).isEqualTo("PENDING");
                        }));
        assertThat(demandTrace.delayReasons()).anySatisfy(reason ->
                assertThat(reason).contains("未核验计划数量 20"));
        assertThat(demandTrace.facts()).isEmpty();
    }

    @Test
    @DisplayName("核验日期晚于工作日期时标记延迟核验")
    void delayedVerificationIsIdentified() {
        ProductionDemandRef demand = createNewProductDemand("ORD-TR-3", TargetProcess.MAKING, 10);
        EmployeeRef employee = hireEmployee("王五", "MAKING");
        TaskRef task = asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION, new SubjectRef(SubjectType.EMPLOYEE, employee.employeeBusinessId()),
                WORK_DATE, TargetProcess.MAKING,
                List.of(new com.yumi.production.api.TaskItemRequest(
                        demand.productionSourceBusinessId(), 10)), null, null, null)));
        String itemId = asAdmin(() -> taskService.getTask(task.taskBusinessId()))
                .items().getFirst().taskItemBusinessId();
        asAdmin(() -> verificationService.verify(new VerifyTaskCommand(
                List.of(new TaskItemResultRequest(itemId, 0, 10, 0, 0, List.of(), null)))));

        var trace = asAdmin(() -> productionQuery.traceOfOrderItem("ORD-TR-3"));
        var demandTrace = trace.demands().getFirst();
        assertThat(demandTrace.tasks().getFirst().workDate()).isEqualTo(WORK_DATE);
        assertThat(demandTrace.delayReasons()).anySatisfy(reason ->
                assertThat(reason).contains("核验日期晚于任务工作日期"));
        assertThat(demandTrace.ledger().fulfilledQuantity()).as("尚未消费").isZero();
        assertThat(demandTrace.ledger().targetReadyQuantity()).as("目标工序就绪 10 件").isEqualTo(10);
    }

    @Test
    @DisplayName("公开投影分页按 (更新时间, 需求标识) 键集推进且不重复")
    void projectionFeedPaginatesWithoutDuplicates() {
        createNewProductDemand("ORD-TR-4", TargetProcess.MAKING, 5);
        createNewProductDemand("ORD-TR-5", TargetProcess.MAKING, 6);

        var firstPage = asAdmin(() -> reportingFeed.listAfter(null, null, 1));
        assertThat(firstPage.rows()).hasSize(1);
        assertThat(firstPage.hasMore()).isTrue();
        var secondPage = asAdmin(() -> reportingFeed.listAfter(
                firstPage.nextUpdatedAt(), firstPage.nextDemandBusinessId(), 1));
        assertThat(secondPage.rows()).hasSize(1);
        assertThat(secondPage.rows().getFirst().demandBusinessId())
                .isNotEqualTo(firstPage.rows().getFirst().demandBusinessId());

        var rows = asAdmin(() -> reportingFeed.listAfter(null, null, 100)).rows();
        assertThat(rows).extracting(ProductionProjectionRow::demandBusinessId).doesNotHaveDuplicates();
        assertThat(rows).allSatisfy(row -> assertThat(row.balanced()).isTrue());
    }

    @Test
    @DisplayName("按产品读取投影，缺口与待核验数量可识别")
    void projectionsOfProductExposeGapAndPendingVerification() {
        var product = createProduct("投影商品", 10, 10);
        ProductionDemandRef demand = createNewProductDemand("ORD-TR-6", TargetProcess.MAKING, 12, product);
        EmployeeRef employee = hireEmployee("赵六", "MAKING");
        TaskRef task = asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION, new SubjectRef(SubjectType.EMPLOYEE, employee.employeeBusinessId()),
                WORK_DATE, TargetProcess.MAKING,
                List.of(new com.yumi.production.api.TaskItemRequest(
                        demand.productionSourceBusinessId(), 12)), null, null, null)));
        String itemId = asAdmin(() -> taskService.getTask(task.taskBusinessId()))
                .items().getFirst().taskItemBusinessId();
        asAdmin(() -> verificationService.verify(new VerifyTaskCommand(
                List.of(new TaskItemResultRequest(itemId, 0, 10, 0, 2, List.of(
                        new DefectGroupRequest("DEFORMED", null, 2, 0, List.of(
                                new DefectGroupRequest.ScrapPartRequest(
                                        2, PhysicalDestination.DESTROYED,
                                        ResponsibilityType.STUDIO, "STUDIO-1", null)))), null)))));

        var rows = asAdmin(() -> productionQuery.projectionsOfProduct(product.productBusinessId(), 10));
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.requiredQuantity()).isEqualTo(12);
            assertThat(row.fulfillmentGapQuantity()).isEqualTo(2);
            assertThat(row.balanced()).isTrue();
        });
    }

    @Test
    @DisplayName("未知订单项或数额非法时明确拒绝")
    void invalidRequestsAreRejected() {
        assertThatThrownBy(() -> asAdmin(() -> productionQuery.traceOfOrderItem("NOT-EXISTS")))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_DEMAND_NOT_FOUND"));
        assertThatThrownBy(() -> asAdmin(() -> reportingFeed.listAfter(null, null, 0)))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("INVALID_LIMIT"));
    }

    private void consume(ProductionDemandRef demand, int quantity) {
        asAdmin(() -> new TransactionTemplate(transactionManager).execute(status -> {
            var prepared = consumption.prepareConsume(new ConsumeProductionCommand(
                    new ProductionLinkRef("orders", "DELIVERY", "DLV-" + demand.demandBusinessId()),
                    demand.productionSourceBusinessId(), quantity));
            return consumption.commitConsume(prepared);
        }));
    }
}

package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.platform.api.http.ApiException;
import com.yumi.production.api.ArrangeTaskCommand;
import com.yumi.production.api.ProductionDemandRef;
import com.yumi.production.api.SubjectRef;
import com.yumi.production.api.SubjectType;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.api.TaskApplicationService;
import com.yumi.production.api.TaskType;
import com.yumi.support.AbstractProductionFixtureTest;
import com.yumi.workforce.api.EmployeeRef;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 已核验负载与未核验预留、以及仅新制制作的商品日上限。
 *
 * <p>规则来源：`docs/一期/11-生产作业模块.md` §6.4.1、§6.6、§6.7。真实 MySQL 验证：
 * <ul>
 *   <li>已核验 6 小时后再排 4 小时必须判为 {@code OVER_STANDARD}，不能按剩余 8 小时放行；</li>
 *   <li>修补、包装与其他任务不占商品新制产能，只有 {@code NEW_PRODUCT + MAKING} 占用；</li>
 *   <li>上限下调后小于已提交数量只阻止新增，不追溯修改历史完成量。</li>
 * </ul>
 */
class DailyLoadAndCapacityIT extends AbstractProductionFixtureTest {

    private static final LocalDate WORK_DATE = LocalDate.parse("2026-10-22");

    @Autowired
    private TaskApplicationService taskService;

    /** 小产能商品：1 模具 × 10 批 = 10 件/日。 */
    private com.yumi.product.api.ProductRef smallProduct;

    @Autowired
    private com.yumi.production.application.DailyLoadService dailyLoadService;

    @Autowired
    private com.yumi.production.application.NewProductCapacityService capacityService;

    @org.junit.jupiter.api.BeforeEach
    void createSmallProduct() {
        smallProduct = createProduct("小产能商品", 1, 10);
    }

    @Test
    @DisplayName("已核验 6 小时后排 4 小时返工必须超标准，不能显示未超 8 小时")
    void verifiedLoadCannotBeIgnored() {
        EmployeeRef employee = hireEmployee("张三", "MAKING", "BAGGING");
        ProductionDemandRef demand = createNewProductDemand("ORD-DL-1", TargetProcess.SEAM_CUTTING, 60);
        markUpstreamQualified(demand.demandBusinessId(), 60);

        // 当日已核验 6 小时（21600 秒）：等价于正常任务已核验完成 6 小时
        asAdmin(() -> {
            var locked = dailyLoadService.lockDays(
                    List.of(employee.employeeBusinessId()), WORK_DATE);
            dailyLoadService.reservePlan(locked, 21_600L);
            dailyLoadService.movePlanToCommitted(
                    employee.employeeBusinessId(), WORK_DATE, 21_600L, 21_600L);
            return null;
        });

        // 再排 48 件 × 5 分钟 = 4 小时：6 + 4 = 10 小时 > 8 小时，必须拒绝
        assertThatThrownBy(() -> asAdmin(() -> taskService.arrange(normalTask(
                employee, WORK_DATE, TargetProcess.BAGGING, demand.productionSourceBusinessId(), 48))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_WORKLOAD_OVER_STANDARD"));

        // 同样 6 小时已核验，再排 24 件 × 5 分钟 = 2 小时 → 8 小时，恰好放行
        asAdmin(() -> taskService.arrange(normalTask(
                employee, WORK_DATE, TargetProcess.BAGGING, demand.productionSourceBusinessId(), 24)));

        var load = dailyLoadService.load(employee.employeeBusinessId(), WORK_DATE);
        assertThat(load.committedSeconds()).as("已核验负载不被释放").isEqualTo(21_600L);
        assertThat(load.reservedSeconds()).isEqualTo(7_200L);
    }

    @Test
    @DisplayName("核验只把完成数量转入已用负载，未完成数量只释放占用")
    void partialCompletionMovesOnlyCompletedWork() {
        EmployeeRef employee = hireEmployee("张三", "MAKING");
        ProductionDemandRef demand = createNewProductDemand("ORD-DL-3", TargetProcess.MAKING, 10);
        asAdmin(() -> taskService.arrange(normalTask(
                employee, WORK_DATE, TargetProcess.MAKING, demand.productionSourceBusinessId(), 10)));

        // 计划 10 件（18000 秒），完成 8 件（14400 秒），未完成 2 件
        asAdmin(() -> {
            dailyLoadService.movePlanToCommitted(
                    employee.employeeBusinessId(), WORK_DATE, 18_000L, 14_400L);
            return null;
        });

        var load = dailyLoadService.load(employee.employeeBusinessId(), WORK_DATE);
        assertThat(load.committedSeconds()).isEqualTo(14_400L);
        assertThat(load.reservedSeconds()).isZero();
    }

    @Test
    @DisplayName("包装任务与修补一样不占新制产能")
    void packagingDoesNotConsumeNewMakingCapacity() {
        EmployeeRef maker = hireEmployee("张三", "MAKING");
        EmployeeRef packer = hireEmployee("李四", "BAGGING");
        ProductionDemandRef demand = createNewProductDemand("ORD-DL-4", TargetProcess.SEAM_CUTTING, 10);

        asAdmin(() -> taskService.arrange(normalTask(
                maker, WORK_DATE, TargetProcess.MAKING, demand.productionSourceBusinessId(), 10)));
        markUpstreamQualified(demand.demandBusinessId(), 10);
        releaseSourceReservation(demand.productionSourceBusinessId(), 10);
        var afterMaking = capacityService.lockOne(
                defaultProduct.productBusinessId(), WORK_DATE, defaultProduct.dailyCapacity(),
                defaultProduct.contentVersion());
        assertThat(afterMaking.reservedQuantity()).isEqualTo(10);

        // 包装工序处理的是已存在制品，不再占用新制产能
        asAdmin(() -> taskService.arrange(normalTask(
                packer, WORK_DATE, TargetProcess.BAGGING, demand.productionSourceBusinessId(), 10)));
        var afterBagging = capacityService.lockOne(
                defaultProduct.productBusinessId(), WORK_DATE, defaultProduct.dailyCapacity(),
                defaultProduct.contentVersion());
        assertThat(afterBagging.usedQuantity())
                .as("包装不增加新制占用")
                .isEqualTo(afterMaking.usedQuantity());
    }

    @Test
    @DisplayName("商品日上限：新制已用 + 预留不得超过上限")
    void newMakingCapacityIsEnforced() {
        EmployeeRef employee = hireEmployee("张三", "MAKING");
        ProductionDemandRef demand = createNewProductDemand(
                "ORD-DL-6", TargetProcess.MAKING, 20, smallProduct);

        asAdmin(() -> taskService.arrange(normalTask(
                employee, WORK_DATE, TargetProcess.MAKING, demand.productionSourceBusinessId(), 10,
                smallProduct)));

        EmployeeRef second = hireEmployee("李四", "MAKING");
        assertThatThrownBy(() -> asAdmin(() -> taskService.arrange(normalTask(
                second, WORK_DATE, TargetProcess.MAKING, demand.productionSourceBusinessId(), 1,
                smallProduct))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_CAPACITY_EXCEEDED"));
    }

    @Test
    @DisplayName("上限下调小于已提交数量时只阻止新增，不追溯修改历史完成量")
    void reducedLimitBlocksNewArrangementOnly() {
        EmployeeRef employee = hireEmployee("张三", "MAKING");
        ProductionDemandRef demand = createNewProductDemand(
                "ORD-DL-7", TargetProcess.MAKING, 10, smallProduct);
        asAdmin(() -> taskService.arrange(normalTask(
                employee, WORK_DATE, TargetProcess.MAKING, demand.productionSourceBusinessId(), 10,
                smallProduct)));

        var lock = capacityService.lockOne(
                smallProduct.productBusinessId(), WORK_DATE, smallProduct.dailyCapacity(),
                smallProduct.contentVersion());
        asAdmin(() -> {
            capacityService.movePlanToCommitted(lock, 10, 10);
            return null;
        });

        var reduced = capacityService.lockOne(
                smallProduct.productBusinessId(), WORK_DATE, 5, smallProduct.contentVersion());
        assertThat(reduced.committedQuantity()).as("已提交数量不被追溯清零").isEqualTo(10);
        assertThatThrownBy(() -> asAdmin(() -> {
            capacityService.reservePlan(reduced, 1);
            return null;
        }))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_CAPACITY_CONFIG_CONFLICT"));
    }

    @Test
    @DisplayName("其他任务不关联商品时也不占新制产能")
    void otherTaskDoesNotConsumeCapacity() {
        EmployeeRef employee = hireEmployee("张三");
        asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.OTHER_TASK, employeeSubject(employee), WORK_DATE, null, List.of(),
                60, new ArrangeTaskCommand.OtherTaskRequest("打包发货", null, null), null)));

        var usage = capacityService.lockOne(
                defaultProduct.productBusinessId(), WORK_DATE, defaultProduct.dailyCapacity(),
                defaultProduct.contentVersion());
        assertThat(usage.usedQuantity()).isZero();
    }

    private ArrangeTaskCommand normalTask(
            EmployeeRef employee, LocalDate workDate, TargetProcess process,
            String productionSourceBusinessId, int quantity) {
        return normalTask(employee, workDate, process, productionSourceBusinessId, quantity, null);
    }

    private ArrangeTaskCommand normalTask(
            EmployeeRef employee, LocalDate workDate, TargetProcess process,
            String productionSourceBusinessId, int quantity,
            com.yumi.product.api.ProductRef ignored) {
        return new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION, employeeSubject(employee), workDate, process,
                List.of(new com.yumi.production.api.TaskItemRequest(productionSourceBusinessId, quantity)),
                null, null, null);
    }

    private static SubjectRef employeeSubject(EmployeeRef employee) {
        return new SubjectRef(SubjectType.EMPLOYEE, employee.employeeBusinessId());
    }
}

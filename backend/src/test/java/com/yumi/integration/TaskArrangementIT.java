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
import com.yumi.production.api.TaskRef;
import com.yumi.production.api.TaskStatus;
import com.yumi.production.api.TaskType;
import com.yumi.production.api.TaskView;
import com.yumi.support.AbstractProductionFixtureTest;
import com.yumi.workforce.api.EmployeeRef;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 任务安排：生产/返工绑定来源并预留；其他任务无生产需求与来源，只按整体分钟预留。
 *
 * <p>规则来源：`docs/一期/11-生产作业模块.md` §6.1、§6.2、§6.2.1、§7.1、§10。
 * 真实 MySQL 验证：来源预留原子占用、任务槽位互斥、其他任务不关联来源。
 */
class TaskArrangementIT extends AbstractProductionFixtureTest {

    private static final LocalDate WORK_DATE = LocalDate.parse("2026-10-21");

    @Autowired
    private TaskApplicationService taskService;

    @Test
    @DisplayName("正常生产任务绑定来源并预留数量，明细冻结快照与标准分钟")
    void normalProductionTaskBindsSourceAndReserves() {
        EmployeeRef employee = hireEmployee("张三", "MAKING");
        ProductionDemandRef demand = createNewProductDemand("ORD-TA-1", TargetProcess.SEAM_CUTTING, 16);

        TaskRef task = asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION, employeeSubject(employee), WORK_DATE, TargetProcess.MAKING,
                List.of(new com.yumi.production.api.TaskItemRequest(demand.productionSourceBusinessId(), 16)),
                null, null, null)));

        assertThat(task.status()).isEqualTo(TaskStatus.ARRANGED);
        assertThat(task.taskNo()).startsWith("T");
        assertThat(task.plannedWorkQuantity()).isEqualTo(16);
        assertThat(task.taskStandardSeconds())
                .as("16 件 × 30 分钟 × 60")
                .isEqualTo(28_800L);

        TaskView view = asAdmin(() -> taskService.getTask(task.taskBusinessId()));
        assertThat(view.items()).singleElement().satisfies(item -> {
            assertThat(item.productionSourceBusinessId()).isEqualTo(demand.productionSourceBusinessId());
            assertThat(item.itemStandardMinutes()).isEqualTo(30);
            assertThat(item.plannedQuantity()).isEqualTo(16);
            assertThat(item.targetProcess()).isEqualTo(TargetProcess.SEAM_CUTTING);
            assertThat(item.verificationStatus()).isEqualTo("PENDING");
        });
        assertThat(view.assessment().result()).isEqualTo("WITHIN_STANDARD");

        assertThat(jdbc.sql("""
                        SELECT reserved_quantity FROM production_source_reservations
                        WHERE production_source_business_id = :id
                        """).param("id", demand.productionSourceBusinessId()).query(Integer.class).single())
                .isEqualTo(16);
        assertThat(jdbc.sql("""
                        SELECT reserved_standard_seconds FROM production_employee_day_loads
                        WHERE employee_business_id = :id AND work_date = :date
                        """).param("id", employee.employeeBusinessId())
                .param("date", java.sql.Date.valueOf(WORK_DATE)).query(Long.class).single())
                .isEqualTo(28_800L);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM workforce_activity_reservations
                        WHERE source_business_id = :taskId AND status = 'ACTIVE'
                        """).param("taskId", task.taskBusinessId()).query(Long.class).single())
                .as("任务持有活动预留")
                .isPositive();
    }

    @Test
    @DisplayName("同员工同日只能有一个正常生产任务；其他工序需要换日或换人")
    void onlyOneNormalProductionTaskPerEmployeeDay() {
        EmployeeRef employee = hireEmployee("张三", "MAKING", "BAGGING");
        ProductionDemandRef first = createNewProductDemand("ORD-TA-2", TargetProcess.SEAM_CUTTING, 10);
        ProductionDemandRef second = createNewProductDemand("ORD-TA-3", TargetProcess.SEAM_CUTTING, 10);

        asAdmin(() -> taskService.arrange(normalTask(employee, WORK_DATE, TargetProcess.MAKING,
                first.productionSourceBusinessId(), 10)));

        assertThatThrownBy(() -> asAdmin(() -> taskService.arrange(normalTask(
                employee, WORK_DATE, TargetProcess.BAGGING, second.productionSourceBusinessId(), 10))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_SLOT_CONFLICT"));
    }

    @Test
    @DisplayName("超出标准时间默认拒绝，超额任务不受该限制")
    void overStandardRequiresExtraTaskType() {
        EmployeeRef employee = hireEmployee("张三", "MAKING");
        ProductionDemandRef demand = createNewProductDemand("ORD-TA-4", TargetProcess.MAKING, 20);

        // 20 件 × 30 分钟 = 600 分钟 > 480 分钟
        assertThatThrownBy(() -> asAdmin(() -> taskService.arrange(normalTask(
                employee, WORK_DATE, TargetProcess.MAKING, demand.productionSourceBusinessId(), 20))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_WORKLOAD_OVER_STANDARD"));

        asAdmin(() -> taskService.arrange(normalTask(
                employee, WORK_DATE, TargetProcess.MAKING, demand.productionSourceBusinessId(), 10)));
        TaskRef extra = asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.EXTRA_PRODUCTION, employeeSubject(employee), WORK_DATE, TargetProcess.MAKING,
                List.of(new com.yumi.production.api.TaskItemRequest(demand.productionSourceBusinessId(), 10)),
                null, null, null)));
        assertThat(extra.taskType()).isEqualTo(TaskType.EXTRA_PRODUCTION);
    }

    @Test
    @DisplayName("超额任务必须有同日制作正常任务，且不能超出商品单日产能")
    void extraTaskRequiresNormalMakingDependencyAndCapacity() {
        EmployeeRef employee = hireEmployee("张三", "MAKING");
        ProductionDemandRef demand = createNewProductDemand("ORD-TA-5", TargetProcess.MAKING, 40);

        assertThatThrownBy(() -> asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.EXTRA_PRODUCTION, employeeSubject(employee), WORK_DATE, TargetProcess.MAKING,
                List.of(new com.yumi.production.api.TaskItemRequest(demand.productionSourceBusinessId(), 5)),
                null, null, null))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_SLOT_DEPENDENCY_MISSING"));
    }

    @Test
    @DisplayName("其他任务不关联生产需求与来源，仅按整体分钟占用负载与槽位")
    void otherTaskHasNoProductionSource() {
        EmployeeRef employee = hireEmployee("张三", "MAKING");

        TaskRef task = asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.OTHER_TASK, employeeSubject(employee), WORK_DATE, null, List.of(),
                120, new ArrangeTaskCommand.OtherTaskRequest("打包发货", null, null), null)));

        assertThat(task.taskStandardSeconds()).isEqualTo(7_200L);
        assertThat(task.processCode()).isNull();
        TaskView view = asAdmin(() -> taskService.getTask(task.taskBusinessId()));
        assertThat(view.items()).isEmpty();
        assertThat(view.otherTask().workContent()).isEqualTo("打包发货");
        assertThat(view.otherTask().plannedQuantity()).isNull();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM production_task_items WHERE task_business_id = :id")
                .param("id", task.taskBusinessId()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM production_active_subject_references WHERE task_business_id = :id
                        """).param("id", task.taskBusinessId()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT work_mode FROM production_employee_day_work_modes
                        WHERE employee_business_id = :id AND work_date = :date
                        """).param("id", employee.employeeBusinessId())
                .param("date", java.sql.Date.valueOf(WORK_DATE)).query(String.class).single())
                .isEqualTo("OTHER_TASK");
    }

    @Test
    @DisplayName("其他任务与生产任务同日互斥")
    void otherTaskAndProductionAreMutuallyExclusive() {
        EmployeeRef employee = hireEmployee("张三", "MAKING");
        ProductionDemandRef demand = createNewProductDemand("ORD-TA-6", TargetProcess.MAKING, 5);
        asAdmin(() -> taskService.arrange(normalTask(
                employee, WORK_DATE, TargetProcess.MAKING, demand.productionSourceBusinessId(), 5)));

        assertThatThrownBy(() -> asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.OTHER_TASK, employeeSubject(employee), WORK_DATE, null, List.of(),
                60, new ArrangeTaskCommand.OtherTaskRequest("打包发货", null, null), null))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_SLOT_CONFLICT"));
    }

    @Test
    @DisplayName("来源可安排数量不足时拒绝，不做 max(0) 截断")
    void insufficientSourceQuantityRejected() {
        EmployeeRef employee = hireEmployee("张三", "MAKING");
        ProductionDemandRef demand = createNewProductDemand("ORD-TA-7", TargetProcess.MAKING, 10);

        assertThatThrownBy(() -> asAdmin(() -> taskService.arrange(normalTask(
                employee, WORK_DATE, TargetProcess.MAKING, demand.productionSourceBusinessId(), 11))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_SOURCE_INSUFFICIENT"));
        assertThat(jdbc.sql("""
                        SELECT COALESCE(SUM(reserved_quantity), 0) FROM production_source_reservations
                        """).query(Long.class).single())
                .as("失败不留占用")
                .isZero();
    }

    @Test
    @DisplayName("同一来源被两个任务重复占用时第二个被拒绝")
    void sameSourceCannotBeOverReserved() {
        EmployeeRef first = hireEmployee("张三", "MAKING");
        EmployeeRef second = hireEmployee("李四", "MAKING");
        ProductionDemandRef demand = createNewProductDemand("ORD-TA-8", TargetProcess.MAKING, 10);

        asAdmin(() -> taskService.arrange(normalTask(
                first, WORK_DATE, TargetProcess.MAKING, demand.productionSourceBusinessId(), 7)));

        assertThatThrownBy(() -> asAdmin(() -> taskService.arrange(normalTask(
                second, WORK_DATE, TargetProcess.MAKING, demand.productionSourceBusinessId(), 4))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_SOURCE_INSUFFICIENT"));
    }

    @Test
    @DisplayName("无资格员工不能安排任务")
    void unqualifiedEmployeeRejected() {
        EmployeeRef employee = hireEmployee("张三");
        ProductionDemandRef demand = createNewProductDemand("ORD-TA-9", TargetProcess.MAKING, 5);

        assertThatThrownBy(() -> asAdmin(() -> taskService.arrange(normalTask(
                employee, WORK_DATE, TargetProcess.MAKING, demand.productionSourceBusinessId(), 5))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("执行工序必须在目标工序链内")
    void processMustBeWithinTargetChain() {
        EmployeeRef employee = hireEmployee("张三", "SEAM_CUTTING");
        ProductionDemandRef demand = createNewProductDemand("ORD-TA-10", TargetProcess.MAKING, 5);

        assertThatThrownBy(() -> asAdmin(() -> taskService.arrange(normalTask(
                employee, WORK_DATE, TargetProcess.SEAM_CUTTING,
                demand.productionSourceBusinessId(), 5))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_TASK_TYPE_INVALID"));
    }

    private ArrangeTaskCommand normalTask(
            EmployeeRef employee, LocalDate workDate, TargetProcess process,
            String productionSourceBusinessId, int quantity) {
        return new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION, employeeSubject(employee), workDate, process,
                List.of(new com.yumi.production.api.TaskItemRequest(productionSourceBusinessId, quantity)),
                null, null, null);
    }

    private static SubjectRef employeeSubject(EmployeeRef employee) {
        return new SubjectRef(SubjectType.EMPLOYEE, employee.employeeBusinessId());
    }
}

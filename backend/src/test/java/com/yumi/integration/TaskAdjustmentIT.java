package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.orders.api.OrderRef;
import com.yumi.platform.api.http.ApiException;
import com.yumi.production.api.AdjustTaskCommand;
import com.yumi.production.api.ArrangeTaskCommand;
import com.yumi.production.api.ProductionDemandRef;
import com.yumi.production.api.SubjectRef;
import com.yumi.production.api.SubjectType;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.api.TaskItemRequest;
import com.yumi.production.api.TaskItemResultRequest;
import com.yumi.production.api.TaskRef;
import com.yumi.production.api.TaskStatus;
import com.yumi.production.api.TaskType;
import com.yumi.production.api.TaskView;
import com.yumi.production.api.VerifyTaskCommand;
import com.yumi.support.AbstractOrderFixtureTest;
import com.yumi.workforce.api.EmployeeRef;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 生产任务独立调整：改期、换主体、改工序与改明细。
 *
 * <p>规则来源：`docs/一期/11-生产作业模块.md` §7.2、§7.3、§11。真实 MySQL 验证：
 * 新旧占用键并集锁定后整体重算；已核验事实留在原日期与主体；无核验才能改工序。
 */
class TaskAdjustmentIT extends AbstractOrderFixtureTest {

    private static final LocalDate FIRST_DAY = LocalDate.parse("2026-10-06");
    private static final LocalDate SECOND_DAY = LocalDate.parse("2026-10-07");

    @Test
    @DisplayName("改期换主体并缩减数量后，旧占用全部释放且新占用重新建立")
    void rescheduleSubjectAndQuantityReallocatesOccupancy() {
        OrderRef order = confirmOrderForMaking(3);
        String sourceId = orderSourceId(order);
        EmployeeRef first = hireEmployee("调整甲-" + java.util.UUID.randomUUID(), "MAKING");
        EmployeeRef second = hireEmployee("调整乙-" + java.util.UUID.randomUUID(), "MAKING");
        TaskRef task = arrangeNormal(first, FIRST_DAY, sourceId, 3);
        String itemId = onlyItemId(task.taskBusinessId());

        TaskView adjusted = asAdmin(() -> orderTaskService.adjust(new AdjustTaskCommand(
                task.taskBusinessId(), employeeSubject(second), SECOND_DAY, TargetProcess.MAKING,
                List.of(new AdjustTaskCommand.ItemRequest(itemId, sourceId, 2)),
                null, null, null)));

        assertThat(adjusted.status()).isEqualTo(TaskStatus.ARRANGED);
        assertThat(adjusted.workDate()).isEqualTo(SECOND_DAY);
        assertThat(adjusted.subject()).isEqualTo(employeeSubject(second));
        assertThat(adjusted.plannedWorkQuantity()).isEqualTo(2);
        assertThat(adjusted.taskStandardSeconds()).isEqualTo(3_600L);
        assertThat(orderJdbc.sql("""
                        SELECT active_assignment_version FROM production_tasks
                        WHERE task_business_id = :id
                        """).param("id", task.taskBusinessId()).query(Long.class).single())
                .isEqualTo(2L);
        assertThat(adjusted.items()).singleElement().satisfies(item -> {
            assertThat(item.taskItemBusinessId()).isEqualTo(itemId);
            assertThat(item.plannedQuantity()).isEqualTo(2);
            assertThat(item.verificationStatus()).isEqualTo("PENDING");
        });

        assertThat(sourceReserved(sourceId)).isEqualTo(2);
        assertThat(holdQuantity(itemId)).isEqualTo(2);
        assertThat(loadReserved(first.employeeBusinessId(), FIRST_DAY)).isZero();
        assertThat(loadReserved(second.employeeBusinessId(), SECOND_DAY)).isEqualTo(3_600L);
        assertThat(slotStatus(first.employeeBusinessId(), FIRST_DAY, "NORMAL_PRODUCTION"))
                .isEqualTo("RELEASED");
        assertThat(slotStatus(second.employeeBusinessId(), SECOND_DAY, "NORMAL_PRODUCTION"))
                .isEqualTo("ACTIVE");
        assertThat(workMode(first.employeeBusinessId(), FIRST_DAY)).isNull();
        assertThat(workMode(second.employeeBusinessId(), SECOND_DAY)).isEqualTo("PRODUCTION");
        assertThat(capacityReserved(FIRST_DAY)).isZero();
        assertThat(capacityReserved(SECOND_DAY)).isEqualTo(2);
        assertThat(activeReservationEmployees(task.taskBusinessId()))
                .containsExactly(second.employeeBusinessId());
        assertThat(assignmentFacts(task.taskBusinessId())).hasSize(2);
        assertThat(orderJdbc.sql("""
                        SELECT old_work_date, new_work_date, operation_type
                        FROM production_task_assignment_facts
                        WHERE task_business_id = :id AND assignment_version = 2
                        """).param("id", task.taskBusinessId())
                .query((resultSet, rowNumber) -> List.of(
                        String.valueOf(resultSet.getDate("old_work_date")),
                        String.valueOf(resultSet.getDate("new_work_date")),
                        resultSet.getString("operation_type")))
                .single())
                .containsExactly(FIRST_DAY.toString(), SECOND_DAY.toString(), "ADJUST");
    }

    @Test
    @DisplayName("调整明细可修改、增加与删除未核验来源，历史安排事实保留")
    void adjustItemsAddsUpdatesAndCancelsUnverifiedItems() {
        OrderRef firstOrder = confirmOrderForMaking(2);
        OrderRef removedOrder = confirmOrderForMaking(2);
        OrderRef addedOrder = confirmOrderForMaking(1);
        String firstSource = orderSourceId(firstOrder);
        String removedSource = orderSourceId(removedOrder);
        String addedSource = orderSourceId(addedOrder);
        EmployeeRef employee = hireEmployee("调整明细-" + java.util.UUID.randomUUID(), "MAKING");
        TaskRef task = asAdmin(() -> orderTaskService.arrange(new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION, employeeSubject(employee), FIRST_DAY,
                TargetProcess.MAKING,
                List.of(new TaskItemRequest(firstSource, 1),
                        new TaskItemRequest(removedSource, 1)),
                null, null, null)));
        TaskView before = asAdmin(() -> orderTaskService.getTask(task.taskBusinessId()));
        String keptItemId = itemOfSource(before, firstSource);
        String removedItemId = itemOfSource(before, removedSource);

        TaskView adjusted = asAdmin(() -> orderTaskService.adjust(new AdjustTaskCommand(
                task.taskBusinessId(), employeeSubject(employee), FIRST_DAY, TargetProcess.MAKING,
                List.of(new AdjustTaskCommand.ItemRequest(keptItemId, firstSource, 2),
                        new AdjustTaskCommand.ItemRequest(null, addedSource, 1)),
                null, null, null)));

        assertThat(adjusted.plannedWorkQuantity()).isEqualTo(3);
        assertThat(adjusted.taskStandardSeconds()).isEqualTo(5_400L);
        assertThat(adjusted.items()).hasSize(3);
        assertThat(itemStatus(keptItemId)).isEqualTo("PENDING");
        assertThat(itemQuantity(keptItemId)).isEqualTo(2);
        assertThat(itemStatus(removedItemId)).isEqualTo("CANCELLED");
        assertThat(itemQuantity(removedItemId)).isZero();
        String addedItemId = itemOfSource(adjusted, addedSource);
        assertThat(itemStatus(addedItemId)).isEqualTo("PENDING");
        assertThat(itemAssignmentVersion(addedItemId)).isEqualTo(2L);
        assertThat(itemAssignmentVersion(removedItemId)).isEqualTo(2L);
        assertThat(sourceReserved(firstSource)).isEqualTo(2);
        assertThat(sourceReserved(removedSource)).isZero();
        assertThat(sourceReserved(addedSource)).isEqualTo(1);
        assertThat(holdQuantity(keptItemId)).isEqualTo(2);
        assertThat(holdStatus(removedItemId)).isEqualTo("RELEASED");
        assertThat(holdQuantity(addedItemId)).isEqualTo(1);
        assertThat(orderJdbc.sql("""
                        SELECT COUNT(*) FROM production_workload_assessment_items a
                        JOIN production_workload_assessments h
                          ON h.assessment_business_id = a.assessment_business_id
                        WHERE h.task_business_id = :id AND h.assignment_version = 2
                        """).param("id", task.taskBusinessId()).query(Long.class).single())
                .isEqualTo(2);
        assertThat(orderJdbc.sql("""
                        SELECT COUNT(*) FROM production_task_item_assignment_facts
                        WHERE task_business_id = :id AND assignment_version = 2
                        """).param("id", task.taskBusinessId()).query(Long.class).single())
                .isEqualTo(3);
        assertThat(orderJdbc.sql("""
                        SELECT operation_type FROM production_task_item_assignment_facts
                        WHERE task_business_id = :id AND assignment_version = 2
                        ORDER BY operation_type
                        """).param("id", task.taskBusinessId()).query(String.class).list())
                .containsExactly("ADD", "ADJUST", "REMOVE");

        assertThatThrownBy(() -> asAdmin(() -> orderVerificationService.verify(
                new VerifyTaskCommand(List.of(new TaskItemResultRequest(
                        removedItemId, 0, 0, 0, 0, List.of(), null))))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_TASK_ITEM_CANCELLED"));
    }

    @Test
    @DisplayName("存在核验事实时禁止改工序，但允许改期并保留原日期已核验负载")
    void verifiedFactLocksProcessButAllowsReschedule() {
        String firstSource = orderSourceId(confirmOrderForMaking(1));
        String secondSource = orderSourceId(confirmOrderForMaking(1));
        EmployeeRef employee = hireEmployee("调整核验-" + java.util.UUID.randomUUID(), "MAKING");
        TaskRef task = asAdmin(() -> orderTaskService.arrange(new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION, employeeSubject(employee), FIRST_DAY,
                TargetProcess.MAKING,
                List.of(new TaskItemRequest(firstSource, 1),
                        new TaskItemRequest(secondSource, 1)),
                null, null, null)));
        TaskView before = asAdmin(() -> orderTaskService.getTask(task.taskBusinessId()));
        String verifiedItemId = itemOfSource(before, firstSource);
        String pendingItemId = itemOfSource(before, secondSource);
        asAdmin(() -> orderVerificationService.verify(new VerifyTaskCommand(List.of(
                new TaskItemResultRequest(verifiedItemId, 0, 1, 0, 0, List.of(), null)))));
        assertThat(taskStatus(task.taskBusinessId())).isEqualTo("PARTIALLY_VERIFIED");

        assertThatThrownBy(() -> asAdmin(() -> orderTaskService.adjust(new AdjustTaskCommand(
                task.taskBusinessId(), employeeSubject(employee), FIRST_DAY, TargetProcess.BAGGING,
                List.of(new AdjustTaskCommand.ItemRequest(pendingItemId, secondSource, 1)),
                null, null, null))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_PROCESS_LOCKED_AFTER_VERIFICATION"));

        TaskView adjusted = asAdmin(() -> orderTaskService.adjust(new AdjustTaskCommand(
                task.taskBusinessId(), employeeSubject(employee), SECOND_DAY, TargetProcess.MAKING,
                List.of(new AdjustTaskCommand.ItemRequest(pendingItemId, secondSource, 1)),
                null, null, null)));

        assertThat(adjusted.status()).isEqualTo(TaskStatus.PARTIALLY_VERIFIED);
        assertThat(adjusted.workDate()).isEqualTo(SECOND_DAY);
        assertThat(adjusted.processCode()).isEqualTo(TargetProcess.MAKING);
        assertThat(adjusted.plannedWorkQuantity()).isEqualTo(2);
        assertThat(adjusted.taskStandardSeconds()).isEqualTo(3_600L);
        assertThat(itemStatus(verifiedItemId)).isEqualTo("VERIFIED");
        assertThat(itemStatus(pendingItemId)).isEqualTo("PENDING");
        assertThat(orderJdbc.sql("""
                        SELECT task_work_date FROM production_task_item_verifications
                        WHERE task_item_business_id = :id
                        """).param("id", verifiedItemId).query(LocalDate.class).single())
                .isEqualTo(FIRST_DAY);
        assertThat(loadCommitted(employee.employeeBusinessId(), FIRST_DAY)).isEqualTo(1_800L);
        assertThat(loadReserved(employee.employeeBusinessId(), FIRST_DAY)).isZero();
        assertThat(loadReserved(employee.employeeBusinessId(), SECOND_DAY)).isEqualTo(1_800L);
        assertThat(slotStatus(employee.employeeBusinessId(), FIRST_DAY, "NORMAL_PRODUCTION"))
                .isEqualTo("RETAINED");
        assertThat(slotStatus(employee.employeeBusinessId(), SECOND_DAY, "NORMAL_PRODUCTION"))
                .isEqualTo("ACTIVE");
        assertThat(workMode(employee.employeeBusinessId(), FIRST_DAY)).isEqualTo("PRODUCTION");
        assertThat(capacityCommitted(FIRST_DAY)).isEqualTo(1);
        assertThat(capacityReserved(FIRST_DAY)).isZero();
        assertThat(capacityReserved(SECOND_DAY)).isEqualTo(1);
    }

    @Test
    @DisplayName("正常任务改期前必须先处理依赖它的超额与返工任务")
    void dependentTasksBlockNormalTaskReschedule() {
        OrderRef order = confirmOrderForMaking(3);
        String sourceId = orderSourceId(order);
        EmployeeRef employee = hireEmployee("调整依赖-" + java.util.UUID.randomUUID(), "MAKING");
        TaskRef normal = arrangeNormal(employee, FIRST_DAY, sourceId, 1);
        TaskRef extra = asAdmin(() -> orderTaskService.arrange(new ArrangeTaskCommand(
                TaskType.EXTRA_PRODUCTION, employeeSubject(employee), FIRST_DAY,
                TargetProcess.MAKING, List.of(new TaskItemRequest(sourceId, 1)),
                null, null, null)));
        String normalItem = onlyItemId(normal.taskBusinessId());

        assertThatThrownBy(() -> asAdmin(() -> orderTaskService.adjust(new AdjustTaskCommand(
                normal.taskBusinessId(), employeeSubject(employee), SECOND_DAY,
                TargetProcess.MAKING,
                List.of(new AdjustTaskCommand.ItemRequest(normalItem, sourceId, 1)),
                null, null, null))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_DEPENDENT_TASK_EXISTS"));

        String extraItem = onlyItemId(extra.taskBusinessId());
        assertThatThrownBy(() -> asAdmin(() -> orderTaskService.adjust(new AdjustTaskCommand(
                extra.taskBusinessId(), employeeSubject(employee), SECOND_DAY,
                TargetProcess.MAKING,
                List.of(new AdjustTaskCommand.ItemRequest(extraItem, sourceId, 1)),
                null, null, null))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_SLOT_DEPENDENCY_MISSING"));

        assertThat(orderJdbc.sql("""
                        SELECT work_date FROM production_tasks WHERE task_business_id = :id
                        """).param("id", normal.taskBusinessId()).query(LocalDate.class).single())
                .isEqualTo(FIRST_DAY);
        assertThat(loadReserved(employee.employeeBusinessId(), FIRST_DAY)).isEqualTo(3_600L);
        assertThat(sourceReserved(sourceId)).isEqualTo(2);
    }

    @Test
    @DisplayName("无核验事实时可改工序并按新工序重算标准分钟")
    void adjustProcessRecomputesStandardMinutes() {
        ProductionDemandRef demand = createNewProductDemand(
                "ORD-ADJUST-PROCESS", TargetProcess.SEAM_CUTTING, 2);
        markUpstreamQualified(demand.demandBusinessId(), 2);
        EmployeeRef employee = hireEmployee(
                "调整工序-" + java.util.UUID.randomUUID(), "BAGGING", "SEAM_CUTTING");
        TaskRef task = asAdmin(() -> orderTaskService.arrange(new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION, employeeSubject(employee), FIRST_DAY,
                TargetProcess.BAGGING,
                List.of(new TaskItemRequest(demand.productionSourceBusinessId(), 1)),
                null, null, null)));
        String itemId = onlyItemId(task.taskBusinessId());

        TaskView adjusted = asAdmin(() -> orderTaskService.adjust(new AdjustTaskCommand(
                task.taskBusinessId(), employeeSubject(employee), FIRST_DAY,
                TargetProcess.SEAM_CUTTING,
                List.of(new AdjustTaskCommand.ItemRequest(
                        itemId, demand.productionSourceBusinessId(), 1)),
                null, null, null)));

        assertThat(adjusted.processCode()).isEqualTo(TargetProcess.SEAM_CUTTING);
        assertThat(adjusted.taskStandardSeconds()).isEqualTo(240L);
        assertThat(adjusted.items()).singleElement().satisfies(item -> {
            assertThat(item.itemStandardMinutes()).isEqualTo(4);
            assertThat(item.plannedQuantity()).isEqualTo(1);
        });
        assertThat(loadReserved(employee.employeeBusinessId(), FIRST_DAY)).isEqualTo(240L);
        assertThat(sourceReserved(demand.productionSourceBusinessId())).isEqualTo(1);
        assertThat(capacityReserved(FIRST_DAY)).isZero();
    }

    @Test
    @DisplayName("调整评估失败时整笔回滚，原日期占用与任务计划保持不变")
    void failedAdjustmentRollsBackReleasedOccupancy() {
        OrderRef order = confirmOrderForMaking(20);
        String sourceId = orderSourceId(order);
        EmployeeRef employee = hireEmployee("调整回滚-" + java.util.UUID.randomUUID(), "MAKING");
        TaskRef task = arrangeNormal(employee, FIRST_DAY, sourceId, 1);
        String itemId = onlyItemId(task.taskBusinessId());

        assertThatThrownBy(() -> asAdmin(() -> orderTaskService.adjust(new AdjustTaskCommand(
                task.taskBusinessId(), employeeSubject(employee), FIRST_DAY,
                TargetProcess.MAKING,
                List.of(new AdjustTaskCommand.ItemRequest(itemId, sourceId, 20)),
                null, null, null))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_WORKLOAD_OVER_STANDARD"));

        assertThat(orderJdbc.sql("""
                        SELECT work_date, planned_work_quantity, active_assignment_version
                        FROM production_tasks WHERE task_business_id = :id
                        """).param("id", task.taskBusinessId())
                .query((resultSet, rowNumber) -> List.of(
                        String.valueOf(resultSet.getDate("work_date")),
                        String.valueOf(resultSet.getInt("planned_work_quantity")),
                        String.valueOf(resultSet.getLong("active_assignment_version"))))
                .single())
                .containsExactly(FIRST_DAY.toString(), "1", "1");
        assertThat(itemQuantity(itemId)).isEqualTo(1);
        assertThat(loadReserved(employee.employeeBusinessId(), FIRST_DAY)).isEqualTo(1_800L);
        assertThat(sourceReserved(sourceId)).isEqualTo(1);
        assertThat(capacityReserved(FIRST_DAY)).isEqualTo(1);
        assertThat(slotStatus(employee.employeeBusinessId(), FIRST_DAY, "NORMAL_PRODUCTION"))
                .isEqualTo("ACTIVE");
        assertThat(orderJdbc.sql("""
                        SELECT COUNT(*) FROM production_workload_assessments
                        WHERE task_business_id = :id
                        """).param("id", task.taskBusinessId()).query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("其他任务可改期换人并重算整体标准分钟")
    void adjustOtherTaskReallocatesPlanLoad() {
        EmployeeRef first = hireEmployee("调整其他甲-" + java.util.UUID.randomUUID());
        EmployeeRef second = hireEmployee("调整其他乙-" + java.util.UUID.randomUUID());
        TaskRef task = asAdmin(() -> orderTaskService.arrange(new ArrangeTaskCommand(
                TaskType.OTHER_TASK, employeeSubject(first), FIRST_DAY, null, List.of(), 60,
                new ArrangeTaskCommand.OtherTaskRequest("整理辅料", null, null), null)));

        TaskView adjusted = asAdmin(() -> orderTaskService.adjust(new AdjustTaskCommand(
                task.taskBusinessId(), employeeSubject(second), SECOND_DAY, null, List.of(),
                90, new ArrangeTaskCommand.OtherTaskRequest("整理辅料并盘点", null, null), null)));

        assertThat(adjusted.status()).isEqualTo(TaskStatus.ARRANGED);
        assertThat(adjusted.workDate()).isEqualTo(SECOND_DAY);
        assertThat(adjusted.subject()).isEqualTo(employeeSubject(second));
        assertThat(adjusted.taskStandardSeconds()).isEqualTo(5_400L);
        assertThat(adjusted.otherTask().workContent()).isEqualTo("整理辅料并盘点");
        assertThat(loadReserved(first.employeeBusinessId(), FIRST_DAY)).isZero();
        assertThat(loadReserved(second.employeeBusinessId(), SECOND_DAY)).isEqualTo(5_400L);
        assertThat(slotStatus(first.employeeBusinessId(), FIRST_DAY, "OTHER_TASK"))
                .isEqualTo("RELEASED");
        assertThat(slotStatus(second.employeeBusinessId(), SECOND_DAY, "OTHER_TASK"))
                .isEqualTo("ACTIVE");
        assertThat(workMode(first.employeeBusinessId(), FIRST_DAY)).isNull();
        assertThat(workMode(second.employeeBusinessId(), SECOND_DAY)).isEqualTo("OTHER_TASK");
        assertThat(orderJdbc.sql("""
                        SELECT COUNT(*) FROM production_workload_assessments
                        WHERE task_business_id = :id
                        """).param("id", task.taskBusinessId()).query(Long.class).single())
                .isEqualTo(2);
        assertThat(assignmentFacts(task.taskBusinessId())).hasSize(2);
    }

    private TaskRef arrangeNormal(EmployeeRef employee, LocalDate workDate, String sourceId, int quantity) {
        return asAdmin(() -> orderTaskService.arrange(new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION, employeeSubject(employee), workDate,
                TargetProcess.MAKING, List.of(new TaskItemRequest(sourceId, quantity)),
                null, null, null)));
    }

    private String onlyItemId(String taskBusinessId) {
        return orderJdbc.sql("""
                        SELECT task_item_business_id FROM production_task_items
                        WHERE task_business_id = :id AND verification_status <> 'CANCELLED'
                        """).param("id", taskBusinessId).query(String.class).single();
    }

    private static String itemOfSource(TaskView task, String sourceId) {
        return task.items().stream()
                .filter(item -> sourceId.equals(item.productionSourceBusinessId()))
                .findFirst().orElseThrow().taskItemBusinessId();
    }

    private int sourceReserved(String sourceId) {
        return orderJdbc.sql("""
                        SELECT reserved_quantity FROM production_source_reservations
                        WHERE production_source_business_id = :id
                        """).param("id", sourceId).query(Integer.class).single();
    }

    private int holdQuantity(String itemId) {
        return orderJdbc.sql("""
                        SELECT reserved_quantity FROM production_source_holds
                        WHERE task_item_business_id = :id
                        """).param("id", itemId).query(Integer.class).single();
    }

    private String holdStatus(String itemId) {
        return orderJdbc.sql("""
                        SELECT status FROM production_source_holds
                        WHERE task_item_business_id = :id
                        """).param("id", itemId).query(String.class).single();
    }

    private long loadReserved(String employeeId, LocalDate workDate) {
        return orderJdbc.sql("""
                        SELECT COALESCE(reserved_standard_seconds, 0)
                        FROM production_employee_day_loads
                        WHERE employee_business_id = :id AND work_date = :workDate
                        """).param("id", employeeId).param("workDate", workDate)
                .query(Long.class).optional().orElse(0L);
    }

    private long loadCommitted(String employeeId, LocalDate workDate) {
        return orderJdbc.sql("""
                        SELECT COALESCE(committed_standard_seconds, 0)
                        FROM production_employee_day_loads
                        WHERE employee_business_id = :id AND work_date = :workDate
                        """).param("id", employeeId).param("workDate", workDate)
                .query(Long.class).optional().orElse(0L);
    }

    private String slotStatus(String employeeId, LocalDate workDate, String slotType) {
        return orderJdbc.sql("""
                        SELECT status FROM production_employee_daily_task_slots
                        WHERE employee_business_id = :id AND work_date = :workDate
                          AND slot_type = :slotType
                        """).param("id", employeeId).param("workDate", workDate)
                .param("slotType", slotType).query(String.class).single();
    }

    private String workMode(String employeeId, LocalDate workDate) {
        return orderJdbc.sql("""
                        SELECT work_mode FROM production_employee_day_work_modes
                        WHERE employee_business_id = :id AND work_date = :workDate
                        """).param("id", employeeId).param("workDate", workDate)
                .query(String.class).optional().orElse(null);
    }

    private int capacityReserved(LocalDate workDate) {
        return orderJdbc.sql("""
                        SELECT COALESCE(SUM(reserved_new_making_quantity), 0)
                        FROM production_product_day_capacity_usages
                        WHERE work_date = :workDate
                        """).param("workDate", workDate).query(Integer.class).single();
    }

    private int capacityCommitted(LocalDate workDate) {
        return orderJdbc.sql("""
                        SELECT COALESCE(SUM(committed_new_making_quantity), 0)
                        FROM production_product_day_capacity_usages
                        WHERE work_date = :workDate
                        """).param("workDate", workDate).query(Integer.class).single();
    }

    private List<String> activeReservationEmployees(String taskId) {
        return orderJdbc.sql("""
                        SELECT DISTINCT reserved_business_id FROM workforce_activity_reservations
                        WHERE source_business_id = :id AND status = 'ACTIVE'
                          AND reservation_scope_type = 'EMPLOYEE'
                        ORDER BY reserved_business_id
                        """).param("id", taskId).query(String.class).list();
    }

    private List<String> assignmentFacts(String taskId) {
        return orderJdbc.sql("""
                        SELECT operation_type FROM production_task_assignment_facts
                        WHERE task_business_id = :id ORDER BY assignment_version
                        """).param("id", taskId).query(String.class).list();
    }

    private String itemStatus(String itemId) {
        return orderJdbc.sql("""
                        SELECT verification_status FROM production_task_items
                        WHERE task_item_business_id = :id
                        """).param("id", itemId).query(String.class).single();
    }

    private long itemAssignmentVersion(String itemId) {
        return orderJdbc.sql("""
                        SELECT assignment_version FROM production_task_items
                        WHERE task_item_business_id = :id
                        """).param("id", itemId).query(Long.class).single();
    }

    private int itemQuantity(String itemId) {
        return orderJdbc.sql("""
                        SELECT planned_quantity FROM production_task_items
                        WHERE task_item_business_id = :id
                        """).param("id", itemId).query(Integer.class).single();
    }

    private String taskStatus(String taskId) {
        return orderJdbc.sql("""
                        SELECT status FROM production_tasks WHERE task_business_id = :id
                        """).param("id", taskId).query(String.class).single();
    }

    private static SubjectRef employeeSubject(EmployeeRef employee) {
        return new SubjectRef(SubjectType.EMPLOYEE, employee.employeeBusinessId());
    }
}

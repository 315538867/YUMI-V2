package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.platform.api.http.ApiException;
import com.yumi.production.api.ArrangeTaskCommand;
import com.yumi.production.api.DefectGroupRequest;
import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.ProductionDemandRef;
import com.yumi.production.api.ResponsibilityType;
import com.yumi.production.api.SubjectRef;
import com.yumi.production.api.SubjectType;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.api.TaskApplicationService;
import com.yumi.production.api.TaskItemResultRequest;
import com.yumi.production.api.TaskRef;
import com.yumi.production.api.TaskStatus;
import com.yumi.production.api.TaskType;
import com.yumi.production.api.TaskVerificationService;
import com.yumi.production.api.TaskView;
import com.yumi.production.api.VerificationView;
import com.yumi.production.api.VerifyOtherTaskCommand;
import com.yumi.production.api.VerifyTaskCommand;
import com.yumi.support.AbstractProductionFixtureTest;
import com.yumi.workforce.api.EmployeeRef;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 一次最终核验、未完成释放、三类线上结果与其他任务核验。
 *
 * <p>规则来源：`docs/一期/11-生产作业模块.md` §7.4—§7.7。真实 MySQL 验证 100/98/2 样例与
 * 全部数量方程；其他任务不生成制品数量、阶段或材料成本来源。
 */
class TaskVerificationIT extends AbstractProductionFixtureTest {

    private static final LocalDate WORK_DATE = LocalDate.parse("2026-10-23");

    @Autowired
    private TaskApplicationService taskService;

    @Autowired
    private TaskVerificationService verificationService;

    @Test
    @DisplayName("100/98/2 样例：全部数量方程成立，未完成释放占用")
    void hundredNinetyEightTwoSample() {
        EmployeeRef employee = hireEmployee("张三", "MAKING");
        ProductionDemandRef demand = createNewProductDemand("ORD-VF-1", TargetProcess.SEAM_CUTTING, 100,
                createProduct("百件商品", 20, 10), 4);
        TaskRef task = asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION, employeeSubject(employee), WORK_DATE, TargetProcess.MAKING,
                List.of(new com.yumi.production.api.TaskItemRequest(
                        demand.productionSourceBusinessId(), 100)),
                null, null, null)));
        TaskView view = asAdmin(() -> taskService.getTask(task.taskBusinessId()));
        String itemId = view.items().getFirst().taskItemBusinessId();

        List<DefectGroupRequest> defects = List.of(
                new DefectGroupRequest("GLUE_OVERFLOW", null, 4, 4, List.of()),
                new DefectGroupRequest("BUBBLE", null, 4, 1, List.of(
                        new DefectGroupRequest.ScrapPartRequest(
                                2, PhysicalDestination.DESTROYED,
                                ResponsibilityType.EMPLOYEE,
                                employee.employeeBusinessId(), null),
                        new DefectGroupRequest.ScrapPartRequest(
                                1, PhysicalDestination.TRANSFERRED_OFFLINE_INVENTORY,
                                ResponsibilityType.WAIVED, null, "客户提供的材料已确认",
                                "瑕疵品仓", null, "张三", "李四", LocalDate.parse("2026-10-06")))));
        VerifyTaskCommand verify = new VerifyTaskCommand(
                List.of(new TaskItemResultRequest(itemId, 2, 90, 5, 3, defects, null)));
        VerificationView result = asAdmin(() -> verificationService.verify(verify));

        assertThat(result.taskStatus()).isEqualTo(TaskStatus.VERIFIED);
        var item = result.items().getFirst();
        assertThat(item.plannedQuantity()).isEqualTo(100);
        assertThat(item.unfinishedQuantity()).isEqualTo(2);
        assertThat(item.completedQuantity()).isEqualTo(98);
        assertThat(item.qualifiedQuantity() + item.reworkQuantity() + item.scrappedQuantity())
                .isEqualTo(98);
        assertThat(item.destroyedQuantity()).isEqualTo(2);
        assertThat(item.scrapOfflineQuantity()).isEqualTo(1);
        assertThat(item.destroyedQuantity() + item.scrapOfflineQuantity()).isEqualTo(3);
        assertThat(item.defects()).hasSize(2);
        assertThat(item.defects().stream().mapToInt(VerificationView.DefectView::quantity).sum())
                .as("缺陷数量合计等于返工与报废之和")
                .isEqualTo(8);
        assertThat(item.repairSourceBusinessIds()).hasSize(2);

        // 数据库侧的数量方程与事实
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM production_task_item_verifications
                        WHERE task_item_business_id = :id
                        """).param("id", itemId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT quantity FROM production_execution_facts WHERE task_item_business_id = :id
                        """).param("id", itemId).query(Integer.class).single()).isEqualTo(98);
        assertThat(jdbc.sql("""
                        SELECT quantity FROM production_terminal_exits WHERE task_item_business_id = :id
                        """).param("id", itemId).query(Integer.class).list())
                .containsExactlyInAnyOrder(2, 1);
        assertThat(jdbc.sql("""
                        SELECT quantity FROM production_responsibility_facts
                        ORDER BY quantity
                        """).query(Integer.class).list())
                .as("只为报废数量记录责任，返工不产生责任")
                .containsExactly(1, 2);
        assertThat(jdbc.sql("""
                        SELECT responsibility_type FROM production_responsibility_facts
                        WHERE quantity = 1
                        """).query(String.class).single()).isEqualTo("WAIVED");

        // 未完成释放回来源可安排数量；来源预留清零
        assertThat(jdbc.sql("""
                        SELECT reserved_quantity FROM production_source_reservations
                        WHERE production_source_business_id = :id
                        """).param("id", demand.productionSourceBusinessId()).query(Integer.class).single())
                .isZero();
        var projection = jdbc.sql("""
                        SELECT required_quantity, pending_work_quantity, in_process_quantity,
                               target_ready_quantity, fulfillment_gap_quantity
                        FROM production_demand_quantity_projections WHERE demand_business_id = :id
                        """).param("id", demand.demandBusinessId())
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getInt("required_quantity"), resultSet.getInt("pending_work_quantity"),
                        resultSet.getInt("in_process_quantity"), resultSet.getInt("target_ready_quantity"),
                        resultSet.getInt("fulfillment_gap_quantity")))
                .single();
        assertThat(projection.get(1)).as("未制数量扣减已完成部分，未完成回到可安排").isEqualTo(2);
        assertThat(projection.get(2)).as("合格与返工进入在制").isEqualTo(95);
        assertThat(projection.get(4)).as("报废形成一次补产缺口").isEqualTo(3);
        assertThat(projection.get(1) + projection.get(2) + projection.get(3) + projection.get(4))
                .as("需求义务守恒")
                .isEqualTo(projection.get(0));

        // 负载：已核验部分进入 committed，未核验部分释放
        var load = jdbc.sql("""
                        SELECT committed_standard_seconds, reserved_standard_seconds
                        FROM production_employee_day_loads
                        WHERE employee_business_id = :id AND work_date = :date
                        """).param("id", employee.employeeBusinessId())
                .param("date", java.sql.Date.valueOf(WORK_DATE))
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getLong("committed_standard_seconds"),
                        resultSet.getLong("reserved_standard_seconds")))
                .single();
        assertThat(load.get(0)).as("98 件 × 4 分钟 × 60").isEqualTo(23_520L);
        assertThat(load.get(1)).isZero();
    }

    @Test
    @DisplayName("每条明细只能提交一次最终核验")
    void itemCanBeVerifiedOnlyOnce() {
        EmployeeRef employee = hireEmployee("张三", "MAKING");
        ProductionDemandRef demand = createNewProductDemand("ORD-VF-2", TargetProcess.MAKING, 5);
        TaskRef task = asAdmin(() -> taskService.arrange(normalTask(employee, demand, 5)));
        String itemId = asAdmin(() -> taskService.getTask(task.taskBusinessId()))
                .items().getFirst().taskItemBusinessId();

        asAdmin(() -> verificationService.verify(new VerifyTaskCommand(
                List.of(new TaskItemResultRequest(itemId, 0, 5, 0, 0, List.of(), null)))));

        assertThatThrownBy(() -> asAdmin(() -> verificationService.verify(new VerifyTaskCommand(
                List.of(new TaskItemResultRequest(itemId, 0, 5, 0, 0, List.of(), null))))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_ITEM_ALREADY_VERIFIED"));
    }

    @Test
    @DisplayName("数量方程不成立时明确拒绝")
    void quantityEquationsAreEnforced() {
        EmployeeRef employee = hireEmployee("张三", "MAKING");
        ProductionDemandRef demand = createNewProductDemand("ORD-VF-3", TargetProcess.MAKING, 10);
        TaskRef task = asAdmin(() -> taskService.arrange(normalTask(employee, demand, 10)));
        String itemId = asAdmin(() -> taskService.getTask(task.taskBusinessId()))
                .items().getFirst().taskItemBusinessId();

        assertThatThrownBy(() -> asAdmin(() -> verificationService.verify(new VerifyTaskCommand(
                List.of(new TaskItemResultRequest(itemId, 1, 8, 0, 0, List.of(), null))))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_QUANTITY_MISMATCH"));

        assertThatThrownBy(() -> asAdmin(() -> verificationService.verify(new VerifyTaskCommand(
                List.of(new TaskItemResultRequest(itemId, 0, 8, 1, 1, List.of(), null))))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_QUANTITY_MISMATCH"));
    }

    @Test
    @DisplayName("缺陷原因必须存在、有效；“其他”必须填写说明")
    void defectReasonIsValidated() {
        EmployeeRef employee = hireEmployee("张三", "MAKING");
        ProductionDemandRef demand = createNewProductDemand("ORD-VF-4", TargetProcess.MAKING, 4);
        TaskRef task = asAdmin(() -> taskService.arrange(normalTask(employee, demand, 4)));
        String itemId = asAdmin(() -> taskService.getTask(task.taskBusinessId()))
                .items().getFirst().taskItemBusinessId();

        assertThatThrownBy(() -> asAdmin(() -> verificationService.verify(new VerifyTaskCommand(
                List.of(new TaskItemResultRequest(itemId, 0, 3, 0, 1, List.of(
                        new DefectGroupRequest("NOT_EXISTS", null, 1, 0, List.of(
                                new DefectGroupRequest.ScrapPartRequest(
                                        1, PhysicalDestination.DESTROYED,
                                        ResponsibilityType.STUDIO, "STUDIO-1", null)))), null))))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_DEFECT_INVALID"));

        assertThatThrownBy(() -> asAdmin(() -> verificationService.verify(new VerifyTaskCommand(
                List.of(new TaskItemResultRequest(itemId, 0, 3, 0, 1, List.of(
                        new DefectGroupRequest("OTHER", null, 1, 0, List.of(
                                new DefectGroupRequest.ScrapPartRequest(
                                        1, PhysicalDestination.DESTROYED,
                                        ResponsibilityType.STUDIO, "STUDIO-1", null)))), null))))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("必须填写说明");
    }

    @Test
    @DisplayName("报废必须明确去向；转线下仍保留报废责任")
    void scrapRequiresDestinationAndKeepsResponsibility() {
        EmployeeRef employee = hireEmployee("张三", "MAKING");
        ProductionDemandRef demand = createNewProductDemand("ORD-VF-5", TargetProcess.MAKING, 6);
        TaskRef task = asAdmin(() -> taskService.arrange(normalTask(employee, demand, 6)));
        String itemId = asAdmin(() -> taskService.getTask(task.taskBusinessId()))
                .items().getFirst().taskItemBusinessId();

        assertThatThrownBy(() -> new DefectGroupRequest.ScrapPartRequest(
                1, PhysicalDestination.IN_SYSTEM, ResponsibilityType.STUDIO, "STUDIO-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("实际销毁或转线下去向");

        asAdmin(() -> verificationService.verify(new VerifyTaskCommand(
                List.of(new TaskItemResultRequest(itemId, 0, 4, 0, 2, List.of(
                        new DefectGroupRequest("DEFORMED", null, 2, 0, List.of(
                                new DefectGroupRequest.ScrapPartRequest(
                                        2, PhysicalDestination.TRANSFERRED_OFFLINE_INVENTORY,
                                        ResponsibilityType.STUDIO, "STUDIO-1", null,
                                        "瑕疵品仓", null, "张三", "李四",
                                        LocalDate.parse("2026-10-06"))))), null)))));

        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM production_terminal_exits
                        WHERE task_item_business_id = :id AND physical_destination = 'TRANSFERRED_OFFLINE_INVENTORY'
                        """).param("id", itemId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM production_offline_inventory_transfers t
                        JOIN production_terminal_exits e
                          ON e.terminal_exit_business_id = t.terminal_exit_business_id
                        WHERE e.task_item_business_id = :id
                        """).param("id", itemId).query(Long.class).single())
                .as("转线下报废必须写移交事实")
                .isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT quantity FROM production_responsibility_facts
                        """).query(Integer.class).single())
                .as("转线下不因去向免扣")
                .isEqualTo(2);
        assertThat(jdbc.sql("""
                        SELECT offline_inventory_quantity FROM production_physical_quantity_projections
                        WHERE physical_root_source_business_id = :id
                        """).param("id", demand.physicalRootSourceBusinessId()).query(Integer.class).single())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("其他任务核验不产生制品数量、阶段或材料成本来源")
    void otherTaskVerificationProducesNoProductionFacts() {
        EmployeeRef employee = hireEmployee("张三");
        TaskRef task = asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.OTHER_TASK, employeeSubject(employee), WORK_DATE, null, List.of(),
                120, new ArrangeTaskCommand.OtherTaskRequest("打包发货", null, null), null)));

        VerificationView result = asAdmin(() -> verificationService.verifyOtherTask(
                new VerifyOtherTaskCommand(task.taskBusinessId(), null, null, "已完成打包")));

        assertThat(result.taskStatus()).isEqualTo(TaskStatus.VERIFIED);
        assertThat(result.otherTask().plannedQuantity()).isNull();
        assertThat(result.otherTask().laborSourceBusinessId())
                .as("未关联商品不生成计件劳动来源")
                .isNull();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM production_execution_facts")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM production_quality_facts")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM production_material_cost_sources")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM production_terminal_exits")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                        SELECT committed_standard_seconds FROM production_employee_day_loads
                        WHERE employee_business_id = :id AND work_date = :date
                        """).param("id", employee.employeeBusinessId())
                .param("date", java.sql.Date.valueOf(WORK_DATE)).query(Long.class).single())
                .as("整体标准负载由计划转为已核验")
                .isEqualTo(7_200L);
    }

    @Test
    @DisplayName("关联商品的其他任务按完成数量生成劳动来源，计划=完成+未完成")
    void otherTaskWithProductGeneratesLaborSource() {
        EmployeeRef employee = hireEmployee("张三");
        var product = createProduct("其他任务商品", 2, 5);
        TaskRef task = asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.OTHER_TASK, employeeSubject(employee), WORK_DATE, null, List.of(),
                90, new ArrangeTaskCommand.OtherTaskRequest(
                        "贴标", product.productBusinessId(), 10), null)));

        VerificationView result = asAdmin(() -> verificationService.verifyOtherTask(
                new VerifyOtherTaskCommand(task.taskBusinessId(), 8, 2, "完成 8 件")));

        assertThat(result.otherTask().plannedQuantity()).isEqualTo(10);
        assertThat(result.otherTask().completedQuantity()).isEqualTo(8);
        assertThat(result.otherTask().laborSourceBusinessId()).isNotNull();
        assertThat(jdbc.sql("SELECT quantity FROM production_labor_sources")
                .query(Integer.class).single()).isEqualTo(8);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM production_material_cost_sources")
                .query(Long.class).single())
                .as("其他任务不生成材料成本来源")
                .isZero();

        assertThatThrownBy(() -> asAdmin(() -> verificationService.verifyOtherTask(
                new VerifyOtherTaskCommand(task.taskBusinessId(), 5, 1, null))))
                .isInstanceOf(ApiException.class);
    }

    private ArrangeTaskCommand normalTask(EmployeeRef employee, ProductionDemandRef demand, int quantity) {
        return new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION, employeeSubject(employee), WORK_DATE, TargetProcess.MAKING,
                List.of(new com.yumi.production.api.TaskItemRequest(
                        demand.productionSourceBusinessId(), quantity)),
                null, null, null);
    }

    private static SubjectRef employeeSubject(EmployeeRef employee) {
        return new SubjectRef(SubjectType.EMPLOYEE, employee.employeeBusinessId());
    }
}

package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.platform.api.http.ApiException;
import com.yumi.production.api.ArrangeTaskCommand;
import com.yumi.production.api.ChangeDefectReasonStatusCommand;
import com.yumi.production.api.CorrectResponsibilityCommand;
import com.yumi.production.api.DefectGroupRequest;
import com.yumi.production.api.DefectReasonAdministrationService;
import com.yumi.production.api.DefectReasonQuery;
import com.yumi.production.api.DefectReasonStatus;
import com.yumi.production.api.DefectDispositionType;
import com.yumi.production.api.DispositionApplicationService;
import com.yumi.production.api.DispositionQuery;
import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.ProductionDemandRef;
import com.yumi.production.api.ResponsibilityApplicationService;
import com.yumi.production.api.ResponsibilityType;
import com.yumi.production.api.SaveDefectReasonCommand;
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

/**
 * 无责任返工、报废责任拆分、销毁与唯一终态退出、缺陷原因启停。
 *
 * <p>规则来源：`docs/一期/11-生产作业模块.md` §7.6、§7.7。真实 MySQL 验证：
 * 5 件缺陷中 3 件返工不归责、报废转线下只退出一次、责任免除不改物理量、缺陷与责任各自追溯。
 */
class ProductionDispositionIT extends AbstractProductionFixtureTest {

    private static final LocalDate WORK_DATE = LocalDate.parse("2026-10-24");

    @Autowired
    private TaskApplicationService taskService;

    @Autowired
    private TaskVerificationService verificationService;

    @Autowired
    private DispositionApplicationService dispositionService;

    @Autowired
    private DispositionQuery dispositionQuery;

    @Autowired
    private ResponsibilityApplicationService responsibilityService;

    @Autowired
    private DefectReasonAdministrationService defectReasons;

    @Autowired
    private DefectReasonQuery defectReasonQuery;

    @Test
    @DisplayName("5 件缺陷中 3 件返工不归责，2 件报废转线下只退出一次")
    void reworkCarriesNoResponsibilityAndScrapExitsOnce() {
        EmployeeRef employee = hireEmployee("张三", "MAKING");
        ProductionDemandRef demand = createNewProductDemand("ORD-DS-1", TargetProcess.MAKING, 12);
        TaskRef task = asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION, employeeSubject(employee), WORK_DATE, TargetProcess.MAKING,
                List.of(new com.yumi.production.api.TaskItemRequest(
                        demand.productionSourceBusinessId(), 12)), null, null, null)));
        String itemId = taskItemId(task);

        DefectGroupRequest defect = new DefectGroupRequest("GLUE_OVERFLOW", null, 5, 3, List.of(
                new DefectGroupRequest.ScrapPartRequest(
                        2, PhysicalDestination.TRANSFERRED_OFFLINE_INVENTORY,
                        ResponsibilityType.EMPLOYEE, employee.employeeBusinessId(), null,
                        "瑕疵品仓", null, "张三", "李四", LocalDate.parse("2026-10-06"))));
        asAdmin(() -> verificationService.verify(new VerifyTaskCommand(
                List.of(new TaskItemResultRequest(itemId, 0, 7, 3, 2, List.of(defect), null)))));

        // 缺陷数量 5 = 返工 3 + 报废 2
        assertThat(jdbc.sql("""
                        SELECT quantity FROM production_defect_facts WHERE task_item_business_id = :id
                        """).param("id", itemId).query(Integer.class).single()).isEqualTo(5);

        // 返工不生成责任：责任事实只有报废的 2 件
        assertThat(jdbc.sql("SELECT COUNT(*) FROM production_responsibility_facts")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT quantity FROM production_responsibility_facts")
                .query(Integer.class).single()).isEqualTo(2);

        // 报废转线下只退出一次：一条终态退出、一次来源扣减
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM production_terminal_exits WHERE task_item_business_id = :id
                        """).param("id", itemId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT physical_destination FROM production_terminal_exits
                        WHERE task_item_business_id = :id
                        """).param("id", itemId).query(String.class).single())
                .isEqualTo("TRANSFERRED_OFFLINE_INVENTORY");
        assertThat(jdbc.sql("""
                        SELECT offline_inventory_quantity FROM production_physical_quantity_projections
                        WHERE physical_root_source_business_id = :id
                        """).param("id", demand.physicalRootSourceBusinessId())
                .query(Integer.class).single())
                .as("转线下只计一次")
                .isEqualTo(2);

        // 处置追溯：返工只关联返工来源，报废带唯一退出与责任
        var trace = asAdmin(() -> dispositionQuery.findByTaskItem(itemId)).getFirst();
        var defectTrace = trace.defects().getFirst();
        assertThat(defectTrace.reworkQuantity()).isEqualTo(3);
        assertThat(defectTrace.scrappedQuantity()).isEqualTo(2);
        assertThat(defectTrace.dispositions())
                .filteredOn(row -> row.dispositionType() == DefectDispositionType.REWORK_REQUIRED)
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.responsibilityType()).as("返工不归责").isNull();
                    assertThat(row.repairSourceBusinessId()).isNotNull();
                    assertThat(row.terminalExitBusinessId()).isNull();
                });
        assertThat(defectTrace.dispositions())
                .filteredOn(row -> row.dispositionType() == DefectDispositionType.SCRAPPED)
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.responsibilityType()).isEqualTo(ResponsibilityType.EMPLOYEE);
                    assertThat(row.terminalExitBusinessId()).isNotNull();
                });
    }

    @Test
    @DisplayName("销毁与转线下按数量拆分，去向合计等于线上报废数量")
    void destroyedAndOfflineAreSplitByQuantity() {
        EmployeeRef employee = hireEmployee("张三", "MAKING");
        ProductionDemandRef demand = createNewProductDemand("ORD-DS-2", TargetProcess.MAKING, 10);
        TaskRef task = asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION, employeeSubject(employee), WORK_DATE, TargetProcess.MAKING,
                List.of(new com.yumi.production.api.TaskItemRequest(
                        demand.productionSourceBusinessId(), 10)), null, null, null)));
        String itemId = taskItemId(task);

        DefectGroupRequest defect = new DefectGroupRequest("DEFORMED", null, 4, 0, List.of(
                new DefectGroupRequest.ScrapPartRequest(
                        3, PhysicalDestination.DESTROYED, ResponsibilityType.STUDIO, "STUDIO-1", null),
                new DefectGroupRequest.ScrapPartRequest(
                        1, PhysicalDestination.TRANSFERRED_OFFLINE_INVENTORY,
                        ResponsibilityType.WAIVED, null, "客户要求外观降级接收",
                        "瑕疵品仓", null, "张三", "李四", LocalDate.parse("2026-10-06"))));
        asAdmin(() -> verificationService.verify(new VerifyTaskCommand(
                List.of(new TaskItemResultRequest(itemId, 0, 6, 0, 4, List.of(defect), null)))));

        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM production_terminal_exits WHERE task_item_business_id = :id
                        """).param("id", itemId).query(Long.class).single()).isEqualTo(2);
        var physical = jdbc.sql("""
                        SELECT destroyed_quantity, offline_inventory_quantity, in_system_quantity
                        FROM production_physical_quantity_projections
                        WHERE physical_root_source_business_id = :id
                        """).param("id", demand.physicalRootSourceBusinessId())
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getInt("destroyed_quantity"),
                        resultSet.getInt("offline_inventory_quantity"),
                        resultSet.getInt("in_system_quantity")))
                .single();
        assertThat(physical.get(0)).isEqualTo(3);
        assertThat(physical.get(1)).isEqualTo(1);
        assertThat(physical.get(2)).as("合格 6 + 返工 0").isEqualTo(6);
    }

    @Test
    @DisplayName("责任免除不改物理量，旧链反向、新链生效")
    void waivingResponsibilityKeepsPhysicalQuantities() {
        EmployeeRef employee = hireEmployee("张三", "MAKING");
        ProductionDemandRef demand = createNewProductDemand("ORD-DS-3", TargetProcess.MAKING, 8);
        TaskRef task = asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION, employeeSubject(employee), WORK_DATE, TargetProcess.MAKING,
                List.of(new com.yumi.production.api.TaskItemRequest(
                        demand.productionSourceBusinessId(), 8)), null, null, null)));
        String itemId = taskItemId(task);

        DefectGroupRequest defect = new DefectGroupRequest("BUBBLE", null, 2, 0, List.of(
                new DefectGroupRequest.ScrapPartRequest(
                        2, PhysicalDestination.DESTROYED, ResponsibilityType.EMPLOYEE,
                        employee.employeeBusinessId(), null)));
        asAdmin(() -> verificationService.verify(new VerifyTaskCommand(
                List.of(new TaskItemResultRequest(itemId, 0, 6, 0, 2, List.of(defect), null)))));

        String dispositionId = jdbc.sql("""
                        SELECT fact_business_id FROM production_disposition_facts
                        WHERE task_item_business_id = :id AND disposition_type = 'SCRAPPED'
                        """).param("id", itemId).query(String.class).single();
        var before = jdbc.sql("""
                        SELECT destroyed_quantity, offline_inventory_quantity
                        FROM production_physical_quantity_projections
                        WHERE physical_root_source_business_id = :id
                        """).param("id", demand.physicalRootSourceBusinessId())
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getInt("destroyed_quantity"),
                        resultSet.getInt("offline_inventory_quantity")))
                .single();
        long beforeChain = asAdmin(() -> responsibilityService.findByDisposition(dispositionId)).rowVersion();

        var corrected = asAdmin(() -> responsibilityService.correct(new CorrectResponsibilityCommand(
                dispositionId, ResponsibilityType.WAIVED, null, "设备突发故障，非人为", "现场复核", beforeChain)));
        assertThat(corrected.responsibilityType()).isEqualTo(ResponsibilityType.WAIVED);
        assertThat(corrected.waivedReason()).isEqualTo("设备突发故障，非人为");
        assertThat(corrected.quantity()).as("数量不变").isEqualTo(2);

        var after = jdbc.sql("""
                        SELECT destroyed_quantity, offline_inventory_quantity
                        FROM production_physical_quantity_projections
                        WHERE physical_root_source_business_id = :id
                        """).param("id", demand.physicalRootSourceBusinessId())
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getInt("destroyed_quantity"),
                        resultSet.getInt("offline_inventory_quantity")))
                .single();
        assertThat(after).as("责任免除不改物理量").isEqualTo(before);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM production_terminal_exits WHERE task_item_business_id = :id
                        """).param("id", itemId).query(Long.class).single())
                .as("更正不新增退出")
                .isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM production_responsibility_revisions
                        WHERE disposition_fact_business_id = :id
                        """).param("id", dispositionId).query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("""
                        SELECT effect_type FROM production_responsibility_revisions
                        WHERE disposition_fact_business_id = :id ORDER BY feed_sequence
                        """).param("id", dispositionId).query(String.class).list())
                .containsExactly("APPLY", "REVERSE", "APPLY");
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM production_responsibility_facts
                        WHERE disposition_fact_business_id = :id AND is_valid = 1
                        """).param("id", dispositionId).query(Long.class).single())
                .as("同一处置只有一条有效责任")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("返工处置不能被认定扣款责任")
    void reworkDispositionHasNoResponsibility() {
        EmployeeRef employee = hireEmployee("张三", "MAKING");
        ProductionDemandRef demand = createNewProductDemand("ORD-DS-4", TargetProcess.MAKING, 4);
        TaskRef task = asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION, employeeSubject(employee), WORK_DATE, TargetProcess.MAKING,
                List.of(new com.yumi.production.api.TaskItemRequest(
                        demand.productionSourceBusinessId(), 4)), null, null, null)));
        String itemId = taskItemId(task);
        asAdmin(() -> verificationService.verify(new VerifyTaskCommand(
                List.of(new TaskItemResultRequest(itemId, 0, 3, 1, 0, List.of(
                        new DefectGroupRequest("DIRTY", null, 1, 1, List.of())), null)))));

        String reworkDispositionId = jdbc.sql("""
                        SELECT fact_business_id FROM production_disposition_facts
                        WHERE task_item_business_id = :id AND disposition_type = 'REWORK_REQUIRED'
                        """).param("id", itemId).query(String.class).single();

        assertThatThrownBy(() -> asAdmin(() -> responsibilityService.correct(
                new CorrectResponsibilityCommand(
                        reworkDispositionId, ResponsibilityType.EMPLOYEE,
                        employee.employeeBusinessId(), null, null, 1L))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_RESPONSIBILITY_NOT_APPLICABLE"));

        assertThatThrownBy(() -> asAdmin(() ->
                dispositionService.ensureTerminalExit(reworkDispositionId)))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_DISPOSITION_NOT_APPLICABLE"));
    }

    @Test
    @DisplayName("已存在唯一退出时重复确认幂等，不新增退出")
    void ensuringTerminalExitIsIdempotent() {
        EmployeeRef employee = hireEmployee("张三", "MAKING");
        ProductionDemandRef demand = createNewProductDemand("ORD-DS-5", TargetProcess.MAKING, 6);
        TaskRef task = asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION, employeeSubject(employee), WORK_DATE, TargetProcess.MAKING,
                List.of(new com.yumi.production.api.TaskItemRequest(
                        demand.productionSourceBusinessId(), 6)), null, null, null)));
        String itemId = taskItemId(task);
        asAdmin(() -> verificationService.verify(new VerifyTaskCommand(
                List.of(new TaskItemResultRequest(itemId, 0, 4, 0, 2, List.of(
                        new DefectGroupRequest("MISSING_PART", null, 2, 0, List.of(
                                new DefectGroupRequest.ScrapPartRequest(
                                        2, PhysicalDestination.DESTROYED,
                                        ResponsibilityType.STUDIO, "STUDIO-1", null)))), null)))));

        String dispositionId = jdbc.sql("""
                        SELECT fact_business_id FROM production_disposition_facts
                        WHERE task_item_business_id = :id AND disposition_type = 'SCRAPPED'
                        """).param("id", itemId).query(String.class).single();

        String first = asAdmin(() -> dispositionService.ensureTerminalExit(dispositionId));
        String second = asAdmin(() -> dispositionService.ensureTerminalExit(dispositionId));
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM production_terminal_exits WHERE task_item_business_id = :id
                        """).param("id", itemId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("停用的缺陷原因不能用于新核验，历史事实保留原因名称")
    void disabledDefectReasonBlocksNewVerification() {
        var created = asAdmin(() -> defectReasons.save(new SaveDefectReasonCommand(
                "TEST_ONLY", "临时原因", false, 0L)));
        assertThat(created.status()).isEqualTo(DefectReasonStatus.ACTIVE);

        asAdmin(() -> defectReasons.changeStatus(new ChangeDefectReasonStatusCommand(
                "TEST_ONLY", DefectReasonStatus.INACTIVE, created.rowVersion())));

        assertThat(asAdmin(() -> defectReasonQuery.listActive()))
                .extracting(com.yumi.production.api.DefectReasonView::defectReasonCode)
                .doesNotContain("TEST_ONLY");
        assertThat(asAdmin(() -> defectReasonQuery.listAll()))
                .extracting(com.yumi.production.api.DefectReasonView::defectReasonCode)
                .contains("TEST_ONLY");

        EmployeeRef employee = hireEmployee("张三", "MAKING");
        ProductionDemandRef demand = createNewProductDemand("ORD-DS-6", TargetProcess.MAKING, 3);
        TaskRef task = asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION, employeeSubject(employee), WORK_DATE, TargetProcess.MAKING,
                List.of(new com.yumi.production.api.TaskItemRequest(
                        demand.productionSourceBusinessId(), 3)), null, null, null)));
        String itemId = taskItemId(task);

        assertThatThrownBy(() -> asAdmin(() -> verificationService.verify(new VerifyTaskCommand(
                List.of(new TaskItemResultRequest(itemId, 0, 2, 0, 1, List.of(
                        new DefectGroupRequest("TEST_ONLY", null, 1, 0, List.of(
                                new DefectGroupRequest.ScrapPartRequest(
                                        1, PhysicalDestination.DESTROYED,
                                        ResponsibilityType.STUDIO, "STUDIO-1", null)))), null))))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_DEFECT_INVALID"));
    }

    @Test
    @DisplayName("缺陷与责任分别可追溯，责任更正不改变缺陷事实")
    void defectAndResponsibilityAreTracedSeparately() {
        EmployeeRef employee = hireEmployee("张三", "MAKING");
        ProductionDemandRef demand = createNewProductDemand("ORD-DS-7", TargetProcess.MAKING, 5);
        TaskRef task = asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION, employeeSubject(employee), WORK_DATE, TargetProcess.MAKING,
                List.of(new com.yumi.production.api.TaskItemRequest(
                        demand.productionSourceBusinessId(), 5)), null, null, null)));
        String itemId = taskItemId(task);
        asAdmin(() -> verificationService.verify(new VerifyTaskCommand(
                List.of(new TaskItemResultRequest(itemId, 0, 3, 0, 2, List.of(
                        new DefectGroupRequest("COLOR_MISMATCH", null, 2, 0, List.of(
                                new DefectGroupRequest.ScrapPartRequest(
                                        2, PhysicalDestination.DESTROYED,
                                        ResponsibilityType.EMPLOYEE,
                                        employee.employeeBusinessId(), null)))), null)))));

        String dispositionId = jdbc.sql("""
                        SELECT fact_business_id FROM production_disposition_facts
                        WHERE task_item_business_id = :id AND disposition_type = 'SCRAPPED'
                        """).param("id", itemId).query(String.class).single();
        String defectBefore = jdbc.sql("""
                        SELECT fact_business_id FROM production_defect_facts WHERE task_item_business_id = :id
                        """).param("id", itemId).query(String.class).single();
        var responsibility = asAdmin(() -> responsibilityService.findByDisposition(dispositionId));

        asAdmin(() -> responsibilityService.correct(new CorrectResponsibilityCommand(
                dispositionId, ResponsibilityType.STUDIO, "STUDIO-9", null, "改判工作室",
                responsibility.rowVersion())));

        assertThat(jdbc.sql("""
                        SELECT fact_business_id FROM production_defect_facts WHERE task_item_business_id = :id
                        """).param("id", itemId).query(String.class).single())
                .as("缺陷事实不受责任更正影响")
                .isEqualTo(defectBefore);
        assertThat(jdbc.sql("""
                        SELECT responsibility_type FROM production_responsibility_facts
                        WHERE disposition_fact_business_id = :id AND is_valid = 1
                        """).param("id", dispositionId).query(String.class).single())
                .isEqualTo("STUDIO");
    }

    private String taskItemId(TaskRef task) {
        return asAdmin(() -> taskService.getTask(task.taskBusinessId()))
                .items().getFirst().taskItemBusinessId();
    }

    private static SubjectRef employeeSubject(EmployeeRef employee) {
        return new SubjectRef(SubjectType.EMPLOYEE, employee.employeeBusinessId());
    }
}

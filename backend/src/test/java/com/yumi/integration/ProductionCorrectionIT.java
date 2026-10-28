package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.platform.api.http.ApiException;
import com.yumi.production.api.ArrangeTaskCommand;
import com.yumi.production.api.DefectGroupRequest;
import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.ProductionCorrectionCommand;
import com.yumi.production.api.ProductionCorrectionQuery;
import com.yumi.production.api.ProductionCorrectionResult;
import com.yumi.production.api.ProductionCorrectionService;
import com.yumi.production.api.ProductionDemandRef;
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

/**
 * 反向/替代事实、更正前后投影及劳动、材料、责任完整命令批次 Feed。
 *
 * <p>规则来源：`docs/一期/11-生产作业模块.md` §7.8。真实 MySQL 验证：
 * 报废改非报废只反向旧责任；更正批次完整可重放；历史负载不搬移。
 */
class ProductionCorrectionIT extends AbstractProductionFixtureTest {

    private static final LocalDate WORK_DATE = LocalDate.parse("2026-10-25");

    @Autowired
    private TaskApplicationService taskService;

    @Autowired
    private TaskVerificationService verificationService;

    @Autowired
    private ProductionCorrectionService correctionService;

    @Autowired
    private ProductionCorrectionQuery correctionQuery;

    @Test
    @DisplayName("报废改非报废只反向旧责任，不生成新责任")
    void scrappedToQualifiedReversesResponsibilityOnly() {
        String itemId = arrangedItem("ORD-CR-1", 10);
        asAdmin(() -> verificationService.verify(new VerifyTaskCommand(
                List.of(new TaskItemResultRequest(itemId, 0, 8, 0, 2, List.of(
                        new DefectGroupRequest("BUBBLE", null, 2, 0, List.of(
                                new DefectGroupRequest.ScrapPartRequest(
                                        2, PhysicalDestination.DESTROYED,
                                        ResponsibilityType.STUDIO, "STUDIO-1", null)))), null)))));

        long beforeResponsibility = jdbc.sql("""
                        SELECT COUNT(*) FROM production_responsibility_facts WHERE is_valid = 1
                        """).query(Long.class).single();
        assertThat(beforeResponsibility).isEqualTo(1);

        ProductionCorrectionResult result = asAdmin(() -> correctionService.correct(
                new ProductionCorrectionCommand(itemId,
                        new TaskItemResultRequest(itemId, 0, 10, 0, 0, List.of(), "复检后全部合格"),
                        "复检确认可交付")));

        assertThat(result.responsibilityReversedOnly())
                .as("报废改非报废只反向旧责任")
                .isTrue();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM production_responsibility_facts WHERE is_valid = 1")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM production_responsibility_facts WHERE is_valid = 0")
                .query(Long.class).single())
                .as("旧责任保留为历史")
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM production_terminal_exits WHERE is_valid = 1")
                .query(Long.class).single()).isZero();
        assertThat(result.batchItems())
                .filteredOn(item -> item.itemKind().equals("RESPONSIBILITY"))
                .singleElement()
                .satisfies(item -> assertThat(item.effectType()).isEqualTo("REVERSE"));
    }

    @Test
    @DisplayName("更正前后投影按净差调整，物理守恒成立")
    void projectionsAreAdjustedByNetDifference() {
        ProductionDemandRef demand = createNewProductDemand("ORD-CR-2", TargetProcess.MAKING, 10);
        TaskRef task = arrange(demand, 10);
        String itemId = taskItemId(task);
        asAdmin(() -> verificationService.verify(new VerifyTaskCommand(
                List.of(new TaskItemResultRequest(itemId, 0, 6, 2, 2, List.of(
                        new DefectGroupRequest("GLUE_OVERFLOW", null, 4, 2,
                                List.of(new DefectGroupRequest.ScrapPartRequest(
                                        2, PhysicalDestination.TRANSFERRED_OFFLINE_INVENTORY,
                                        ResponsibilityType.EMPLOYEE,
                                        employeeId(), null,
                                        "瑕疵品仓", null, "张三", "李四",
                                        LocalDate.parse("2026-10-06"))))), null)))));

        var before = physical(demand);
        assertThat(before).containsExactly(10, 8, 0, 0, 2);

        asAdmin(() -> correctionService.correct(new ProductionCorrectionCommand(itemId,
                new TaskItemResultRequest(itemId, 0, 9, 1, 0, List.of(
                        new DefectGroupRequest("GLUE_OVERFLOW", null, 1, 1, List.of())), "返工后合格"),
                "复检：1 件改为返工可修复")));

        var after = physical(demand);
        // 累计制作量不变（10），在制 = 合格 9 + 返工 1，销毁 0，线下 0
        assertThat(after).containsExactly(10, 10, 0, 0, 0);
        assertThat(after.get(1) + after.get(2) + after.get(3) + after.get(4))
                .as("物理守恒")
                .isEqualTo(after.get(0));

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
        assertThat(projection.get(4)).as("缺口回到 0").isZero();
        assertThat(projection.get(1) + projection.get(2) + projection.get(3) + projection.get(4))
                .as("需求义务守恒")
                .isEqualTo(projection.get(0));
    }

    @Test
    @DisplayName("命令批次完整可重放：声明项数等于实际项数，覆盖劳动材料责任")
    void commandBatchIsCompleteAndReplayable() {
        String itemId = arrangedItem("ORD-CR-3", 8);
        asAdmin(() -> verificationService.verify(new VerifyTaskCommand(
                List.of(new TaskItemResultRequest(itemId, 0, 6, 0, 2, List.of(
                        new DefectGroupRequest("DEFORMED", null, 2, 0, List.of(
                                new DefectGroupRequest.ScrapPartRequest(
                                        2, PhysicalDestination.DESTROYED,
                                        ResponsibilityType.STUDIO, "STUDIO-1", null)))), null)))));

        ProductionCorrectionResult result = asAdmin(() -> correctionService.correct(
                new ProductionCorrectionCommand(itemId,
                        new TaskItemResultRequest(itemId, 0, 7, 0, 1, List.of(
                                new DefectGroupRequest("DEFORMED", null, 1, 0, List.of(
                                        new DefectGroupRequest.ScrapPartRequest(
                                                1, PhysicalDestination.DESTROYED,
                                                ResponsibilityType.STUDIO, "STUDIO-1", null)))),
                        "复检 1 件可交付"), "复检确认 1 件可交付")));

        assertThat(result.isComplete()).isTrue();
        assertThat(result.commandFingerprint()).matches("[0-9a-f]{64}");
        assertThat(result.batchItems()).extracting(ProductionCorrectionResult.BatchItem::index)
                .containsExactlyElementsOf(java.util.stream.IntStream
                        .range(0, result.batchItems().size()).boxed().toList());
        assertThat(result.batchItems()).extracting(ProductionCorrectionResult.BatchItem::itemKind)
                .contains("LABOR", "MATERIAL", "RESPONSIBILITY");
        assertThat(result.batchItems()).extracting(ProductionCorrectionResult.BatchItem::effectType)
                .contains("REVERSE", "APPLY");

        var feed = asAdmin(() -> correctionQuery.itemsOf(result.correctionCommandBusinessId()));
        assertThat(feed).hasSize(result.declaredItemCount());
        assertThat(asAdmin(() -> correctionQuery.listAfter(0L, 500))
                .stream().filter(row -> row.correctionCommandBusinessId()
                        .equals(result.correctionCommandBusinessId()))
                .count())
                .isEqualTo(result.declaredItemCount());

        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM production_fact_correction_applications
                        WHERE correction_command_business_id = :id
                        """).param("id", result.correctionCommandBusinessId()).query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("历史负载不搬移：更正结算到原工作日期")
    void historicalLoadStaysOnOriginalWorkDate() {
        String itemId = arrangedItem("ORD-CR-4", 10);
        asAdmin(() -> verificationService.verify(new VerifyTaskCommand(
                List.of(new TaskItemResultRequest(itemId, 1, 9, 0, 0, List.of(), null)))));

        long before = committedLoad(employeeId(), WORK_DATE);
        asAdmin(() -> correctionService.correct(new ProductionCorrectionCommand(itemId,
                new TaskItemResultRequest(itemId, 0, 10, 0, 0, List.of(), "全部完成"),
                "复检确认剩余 1 件已完成")));

        assertThat(committedLoad(employeeId(), WORK_DATE))
                .as("更正结算到原工作日期")
                .isEqualTo(before + 30L * 60L);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM production_employee_day_loads
                        WHERE work_date <> :date
                        """).param("date", java.sql.Date.valueOf(WORK_DATE)).query(Long.class).single())
                .as("没有把负载搬到其他日期")
                .isZero();
    }

    @Test
    @DisplayName("未核验明细不能更正")
    void unverifiedItemCannotBeCorrected() {
        String itemId = arrangedItem("ORD-CR-5", 5);
        assertThatThrownBy(() -> asAdmin(() -> correctionService.correct(
                new ProductionCorrectionCommand(itemId,
                        new TaskItemResultRequest(itemId, 0, 5, 0, 0, List.of(), null), "试图更正"))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_CORRECTION_NOT_APPLICABLE"));
    }

    @Test
    @DisplayName("更正后的数量方程仍必须成立")
    void correctedQuantitiesMustBalance() {
        String itemId = arrangedItem("ORD-CR-6", 6);
        asAdmin(() -> verificationService.verify(new VerifyTaskCommand(
                List.of(new TaskItemResultRequest(itemId, 0, 6, 0, 0, List.of(), null)))));

        assertThatThrownBy(() -> asAdmin(() -> correctionService.correct(
                new ProductionCorrectionCommand(itemId,
                        new TaskItemResultRequest(itemId, 1, 4, 0, 0, List.of(), null), "数量不守恒"))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_QUANTITY_MISMATCH"));
    }

    private String arrangedItem(String orderItemId, int quantity) {
        ProductionDemandRef demand = createNewProductDemand(orderItemId, TargetProcess.MAKING, quantity);
        TaskRef task = arrange(demand, quantity);
        return taskItemId(task);
    }

    private TaskRef arrange(ProductionDemandRef demand, int quantity) {
        EmployeeRef employee = hireEmployee("张三", "MAKING");
        return asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION, employeeSubject(employee), WORK_DATE, TargetProcess.MAKING,
                List.of(new com.yumi.production.api.TaskItemRequest(
                        demand.productionSourceBusinessId(), quantity)), null, null, null)));
    }

    private String taskItemId(TaskRef task) {
        return asAdmin(() -> taskService.getTask(task.taskBusinessId()))
                .items().getFirst().taskItemBusinessId();
    }

    private String employeeId() {
        return jdbc.sql("SELECT employee_business_id FROM workforce_employees ORDER BY id LIMIT 1")
                .query(String.class).single();
    }

    private long committedLoad(String employeeId, LocalDate date) {
        return jdbc.sql("""
                        SELECT committed_standard_seconds FROM production_employee_day_loads
                        WHERE employee_business_id = :id AND work_date = :date
                        """).param("id", employeeId).param("date", java.sql.Date.valueOf(date))
                .query(Long.class).single();
    }

    private List<Integer> physical(ProductionDemandRef demand) {
        return jdbc.sql("""
                        SELECT cumulative_making_created_quantity, in_system_quantity, consumed_quantity,
                               destroyed_quantity, offline_inventory_quantity
                        FROM production_physical_quantity_projections
                        WHERE physical_root_source_business_id = :id
                        """).param("id", demand.physicalRootSourceBusinessId())
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getInt("cumulative_making_created_quantity"),
                        resultSet.getInt("in_system_quantity"), resultSet.getInt("consumed_quantity"),
                        resultSet.getInt("destroyed_quantity"),
                        resultSet.getInt("offline_inventory_quantity")))
                .single();
    }

    private static SubjectRef employeeSubject(EmployeeRef employee) {
        return new SubjectRef(SubjectType.EMPLOYEE, employee.employeeBusinessId());
    }
}

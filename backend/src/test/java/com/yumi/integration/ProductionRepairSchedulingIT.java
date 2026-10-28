package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.platform.api.http.ApiException;
import com.yumi.production.api.ArrangeTaskCommand;
import com.yumi.production.api.AvailableRepairSourceView;
import com.yumi.production.api.CancelTaskCommand;
import com.yumi.production.api.DefectGroupRequest;
import com.yumi.production.api.ProductionDemandRef;
import com.yumi.production.api.SubjectRef;
import com.yumi.production.api.SubjectType;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.api.TaskApplicationService;
import com.yumi.production.api.TaskItemRequest;
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
 * 内部返工来源排班：核验返工处置生成的来源可查询、可安排、可取消释放，并在核验后只结算一次。
 *
 * <p>规则来源：`docs/一期/11-生产作业模块.md` §5.2.1、§6.1、§6.7、§7.6。
 */
class ProductionRepairSchedulingIT extends AbstractProductionFixtureTest {

    private static final LocalDate WORK_DATE = LocalDate.parse("2026-10-26");

    @Autowired
    private TaskApplicationService taskService;

    @Autowired
    private TaskVerificationService verificationService;

    @Test
    @DisplayName("内部返工来源可查询、占用、释放与完成，不重复扣减且不增加累计制作量")
    void internalRepairSourceLifecycle() {
        EmployeeRef employee = hireEmployee("返工排班工", "MAKING");
        var product = createProduct("返工排班商品", 10, 10);
        ProductionDemandRef demand = createNewProductDemand(
                "ORD-RPS-1", TargetProcess.MAKING, 6, product, 5);
        TaskRef normalTask = asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION, subject(employee), WORK_DATE, TargetProcess.MAKING,
                List.of(new TaskItemRequest(demand.productionSourceBusinessId(), 6)),
                null, null, null)));
        String normalItemId = asAdmin(() -> taskService.getTask(normalTask.taskBusinessId()))
                .items().getFirst().taskItemBusinessId();
        asAdmin(() -> verificationService.verify(new VerifyTaskCommand(List.of(
                new TaskItemResultRequest(normalItemId, 0, 4, 2, 0, List.of(
                        new DefectGroupRequest("GLUE_OVERFLOW", null, 2, 2, List.of())), null)))));

        List<AvailableRepairSourceView> sources =
                asAdmin(() -> taskService.availableRepairSources(TargetProcess.MAKING));
        assertThat(sources).hasSize(1);
        AvailableRepairSourceView repair = sources.getFirst();
        String repairSourceId = repair.repairSourceBusinessId();
        assertThat(repair.originType()).isEqualTo("INTERNAL_DEFECT");
        assertThat(repair.repairProcessCode()).isEqualTo(TargetProcess.MAKING);
        assertThat(repair.targetProcess()).isEqualTo(TargetProcess.MAKING);
        assertThat(repair.productBusinessId()).isEqualTo(product.productBusinessId());
        assertThat(repair.availableQuantity()).isEqualTo(2);
        assertThat(repair.reservedQuantity()).isZero();

        assertThatThrownBy(() -> asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.REPAIR, subject(employee), WORK_DATE, TargetProcess.MAKING,
                List.of(new TaskItemRequest(null, repairSourceId, 3)), null, null, null))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_REPAIR_SOURCE_INSUFFICIENT"));

        assertThatThrownBy(() -> asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.REPAIR, subject(employee), WORK_DATE, TargetProcess.BAGGING,
                List.of(new TaskItemRequest(null, repairSourceId, 1)), null, null, null))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_TASK_TYPE_INVALID"));

        TaskRef cancelledTask = arrangeRepair(employee, repairSourceId, 1);
        assertThat(repairReserved(repairSourceId)).isEqualTo(1);
        asAdmin(() -> taskService.cancel(new CancelTaskCommand(
                cancelledTask.taskBusinessId(), "返工安排撤销")));
        assertThat(repairReserved(repairSourceId)).isZero();
        assertThat(repairAvailable(repairSourceId)).isEqualTo(2);

        TaskRef repairTask = arrangeRepair(employee, repairSourceId, 2);
        assertThat(repairReserved(repairSourceId)).isEqualTo(2);
        String repairItemId = asAdmin(() -> taskService.getTask(repairTask.taskBusinessId()))
                .items().getFirst().taskItemBusinessId();
        asAdmin(() -> verificationService.verify(new VerifyTaskCommand(List.of(
                new TaskItemResultRequest(repairItemId, 0, 2, 0, 0, List.of(), null)))));

        var sourceRow = jdbc.sql("""
                        SELECT available_quantity, reserved_quantity, completed_quantity, status
                        FROM production_repair_sources WHERE repair_source_business_id = :id
                        """).param("id", repairSourceId)
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getInt("available_quantity"),
                        resultSet.getInt("reserved_quantity"),
                        resultSet.getInt("completed_quantity"),
                        resultSet.getString("status")))
                .single();
        assertThat(sourceRow.get(0)).as("完成数量从可用数量扣除").isEqualTo(0);
        assertThat(sourceRow.get(1)).as("活动预留全部释放").isEqualTo(0);
        assertThat(sourceRow.get(2)).as("已完成数量只结算一次").isEqualTo(2);
        assertThat(sourceRow.get(3)).isEqualTo("COMPLETED");

        var projection = jdbc.sql("""
                        SELECT pending_work_quantity, in_process_quantity,
                               target_ready_quantity, fulfillment_gap_quantity
                        FROM production_demand_quantity_projections WHERE demand_business_id = :id
                        """).param("id", demand.demandBusinessId())
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getInt("pending_work_quantity"),
                        resultSet.getInt("in_process_quantity"),
                        resultSet.getInt("target_ready_quantity"),
                        resultSet.getInt("fulfillment_gap_quantity")))
                .single();
        assertThat(projection.get(0)).as("返工不回到未制数量").isZero();
        assertThat(projection.get(1)).as("返工完成后退出在制").isZero();
        assertThat(projection.get(2)).as("合格返工回到目标就绪").isEqualTo(6);
        assertThat(projection.get(3)).isZero();

        var physical = jdbc.sql("""
                        SELECT cumulative_making_created_quantity, in_system_quantity
                        FROM production_physical_quantity_projections
                        WHERE physical_root_source_business_id = :id
                        """).param("id", demand.physicalRootSourceBusinessId())
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getInt("cumulative_making_created_quantity"),
                        resultSet.getInt("in_system_quantity")))
                .single();
        assertThat(physical.get(0)).as("返工不增加累计制作量").isEqualTo(6);
        assertThat(physical.get(1)).isEqualTo(6);

        assertThat(jdbc.sql("""
                        SELECT operation_type FROM production_repair_source_facts
                        WHERE repair_source_business_id = :id ORDER BY id
                        """).param("id", repairSourceId).query(String.class).list())
                .containsExactly("CREATED", "RESERVED", "RELEASED", "RESERVED", "COMPLETED");
    }

    private TaskRef arrangeRepair(EmployeeRef employee, String repairSourceId, int quantity) {
        return asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.REPAIR, subject(employee), WORK_DATE, TargetProcess.MAKING,
                List.of(new TaskItemRequest(null, repairSourceId, quantity)),
                null, null, null)));
    }

    private int repairReserved(String repairSourceId) {
        return jdbc.sql("""
                        SELECT reserved_quantity FROM production_repair_sources
                        WHERE repair_source_business_id = :id
                        """).param("id", repairSourceId).query(Integer.class).single();
    }

    private int repairAvailable(String repairSourceId) {
        return jdbc.sql("""
                        SELECT available_quantity FROM production_repair_sources
                        WHERE repair_source_business_id = :id
                        """).param("id", repairSourceId).query(Integer.class).single();
    }

    private static SubjectRef subject(EmployeeRef employee) {
        return new SubjectRef(SubjectType.EMPLOYEE, employee.employeeBusinessId());
    }
}

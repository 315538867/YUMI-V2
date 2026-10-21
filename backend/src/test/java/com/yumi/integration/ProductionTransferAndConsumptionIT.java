package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.platform.api.http.ApiException;
import com.yumi.production.api.ArrangeTaskCommand;
import com.yumi.production.api.ConsumeProductionCommand;
import com.yumi.production.api.ProductionConsumptionParticipant;
import com.yumi.production.api.ProductionDemandRef;
import com.yumi.production.api.ProductionLinkRef;
import com.yumi.production.api.ProductionOfflineInventoryTransferParticipant;
import com.yumi.production.api.ReduceProductionDemandCommand;
import com.yumi.production.api.ReverseConsumptionCommand;
import com.yumi.production.api.SubjectRef;
import com.yumi.production.api.SubjectType;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.api.TaskApplicationService;
import com.yumi.production.api.TaskItemResultRequest;
import com.yumi.production.api.TaskRef;
import com.yumi.production.api.TaskType;
import com.yumi.production.api.TaskVerificationService;
import com.yumi.production.api.TransferProductionToOfflineCommand;
import com.yumi.production.api.TransferReason;
import com.yumi.production.api.VerifyTaskCommand;
import com.yumi.production.application.ProductionOfflineInventoryTransferServiceImpl;
import com.yumi.support.AbstractProductionFixtureTest;
import com.yumi.workforce.api.EmployeeRef;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 合法来源消费、反向消费、订单减量移交与禁止移交更正。
 *
 * <p>规则来源：`docs/一期/11-生产作业模块.md` §5.6、§5.7、§7.7。真实 MySQL 验证：
 * 已移交不能取回、已制减量无移交拒绝、发货失败不留下消费。
 */
class ProductionTransferAndConsumptionIT extends AbstractProductionFixtureTest {

    private static final LocalDate WORK_DATE = LocalDate.parse("2026-10-26");

    @Autowired
    private TaskApplicationService taskService;

    @Autowired
    private TaskVerificationService verificationService;

    @Autowired
    private ProductionConsumptionParticipant consumption;

    @Autowired
    private ProductionOfflineInventoryTransferParticipant transfer;

    @Autowired
    private ProductionOfflineInventoryTransferServiceImpl transferService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("合法来源消费：目标就绪数量转为已交付，物理投影同步")
    void consumingTargetReadyQuantity() {
        ProductionDemandRef demand = verifiedDemand("ORD-TC-1", 10, 10);

        var result = inTransaction(() -> {
            var prepared = consumption.prepareConsume(new ConsumeProductionCommand(
                    delivery("DLV-1"), demand.productionSourceBusinessId(), 4));
            return consumption.commitConsume(prepared);
        });

        assertThat(result.quantity()).isEqualTo(4);
        assertThat(result.consumedQuantity()).isEqualTo(4);
        assertThat(result.availableToConsume()).isEqualTo(6);
        assertThat(physical(demand)).containsExactly(10, 6, 4, 0, 0);
        assertThat(demandProjection(demand).get(2)).as("目标就绪减少").isEqualTo(6);
        assertThat(demandProjection(demand).get(3)).as("已履行增加").isEqualTo(4);
        assertThat(demandProjection(demand).subList(1, 5).stream().mapToInt(Integer::intValue).sum())
                .as("需求义务守恒")
                .isEqualTo(10);
    }

    @Test
    @DisplayName("反向消费只冲销有效消费，数量回到可交付")
    void reversingConsumption() {
        ProductionDemandRef demand = verifiedDemand("ORD-TC-2", 8, 8);
        inTransaction(() -> {
            var prepared = consumption.prepareConsume(new ConsumeProductionCommand(
                    delivery("DLV-2"), demand.productionSourceBusinessId(), 3));
            return consumption.commitConsume(prepared);
        });

        var reversed = inTransaction(() -> {
            var prepared = consumption.prepareReverse(new ReverseConsumptionCommand(
                    delivery("DLV-2"), demand.productionSourceBusinessId(), 3, "发货取消"));
            return consumption.commitReverse(prepared);
        });

        assertThat(reversed.reverse()).isTrue();
        assertThat(reversed.consumedQuantity()).isZero();
        assertThat(reversed.availableToConsume()).isEqualTo(8);
        assertThat(physical(demand)).containsExactly(8, 8, 0, 0, 0);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM production_consumption_facts
                        WHERE is_valid = 1 AND effect_type = 'CONSUME'
                        """).query(Long.class).single())
                .as("原消费事实保留")
                .isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM production_consumption_facts
                        WHERE is_valid = 1 AND effect_type = 'REVERSE'
                          AND reverses_fact_business_id IS NOT NULL
                        """).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT COALESCE(SUM(CASE WHEN effect_type = 'CONSUME' THEN quantity ELSE -quantity END), 0)
                        FROM production_consumption_facts WHERE is_valid = 1
                        """).query(Integer.class).single())
                .as("有效净消费为零")
                .isZero();
        assertThat(jdbc.sql("""
                        SELECT effect_type FROM production_consumption_facts ORDER BY occurred_at
                        """).query(String.class).list()).contains("CONSUME", "REVERSE");
    }

    @Test
    @DisplayName("已移交线下不能取回，也不能更正移交")
    void transferredQuantityCannotBeTakenBack() {
        ProductionDemandRef demand = verifiedDemand("ORD-TC-3", 10, 10);

        String transferId = inTransaction(() -> {
            var command = new TransferProductionToOfflineCommand(
                    demand.productionSourceBusinessId(), 4, TransferReason.ORDER_REDUCTION,
                    "ORD-TC-3-CHANGE", "瑕疵品仓", "订单减量转线下", "张三", "李四",
                    LocalDateTime.parse("2026-10-26T10:00:00"));
            var prepared = transfer.prepareTransfer(command);
            return transfer.commit(command, prepared).transferFactBusinessId();
        });
        assertThat(transferId).isNotNull();
        assertThat(physical(demand)).containsExactly(10, 6, 0, 0, 4);

        // 已移交的 4 件不能再被消费：可消费只剩 6
        assertThatThrownBy(() -> inTransaction(() -> {
            var prepared = consumption.prepareConsume(new ConsumeProductionCommand(
                    delivery("DLV-3"), demand.productionSourceBusinessId(), 7));
            return consumption.commitConsume(prepared);
        }))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_CONSUME_INSUFFICIENT"));

        // 移交事实不可在系统内更正
        assertThatThrownBy(() -> asAdmin(() -> {
            transferService.rejectCorrection(transferId);
            return null;
        }))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_TRANSFER_IRREVERSIBLE"));
    }

    @Test
    @DisplayName("已制减量未提供移交明细时拒绝")
    void reductionWithProducedQuantityRequiresTransfer() {
        ProductionDemandRef demand = verifiedDemand("ORD-TC-4", 10, 10);

        assertThatThrownBy(() -> inTransaction(() -> {
            var preview = asAdmin(() -> demandParticipant.previewReduction(
                    new ReduceProductionDemandCommand(
                            demand.demandBusinessId(), 6, List.of(), "ORDER_REDUCTION", "订单减量")));
            assertThat(preview.transferProducedQuantity()).isZero();
            return asAdmin(() -> demandParticipant.commitReduction(
                    new ReduceProductionDemandCommand(
                            demand.demandBusinessId(), 6, List.of(), "ORDER_REDUCTION", "订单减量")));
        }))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_DEMAND_REDUCTION_BLOCKED"));

        var preview = inTransaction(() -> demandParticipant.previewReduction(
                new ReduceProductionDemandCommand(
                        demand.demandBusinessId(), 6, List.of(), "ORDER_REDUCTION", "订单减量")));
        assertThat(preview.isAllowed()).isFalse();
        assertThat(preview.blockingReasons())
                .anySatisfy(reason -> assertThat(reason).contains("线下移交明细"));
    }

    @Test
    @DisplayName("同时提供移交明细时减量成功，每份数量归入唯一类别")
    void reductionWithTransferSucceeds() {
        ProductionDemandRef demand = verifiedDemand("ORD-TC-5", 10, 7);

        var result = inTransaction(() -> {
            var command = new TransferProductionToOfflineCommand(
                    demand.productionSourceBusinessId(), 3, TransferReason.ORDER_REDUCTION,
                    "ORD-TC-5-CHANGE", "瑕疵品仓", null, "张三", "李四",
                    LocalDateTime.parse("2026-10-26T11:00:00"));
            var prepared = transfer.prepareTransfer(command);
            transfer.commit(command, prepared);
            var preview = asAdmin(() -> demandParticipant.previewReduction(
                    new ReduceProductionDemandCommand(
                            demand.demandBusinessId(), 7,
                            List.of(new ReduceProductionDemandCommand.TransferAllocation(
                                    demand.productionSourceBusinessId(), 3)),
                            "ORDER_REDUCTION", "订单减量")));
            assertThat(preview.isAllowed()).isTrue();
            return asAdmin(() -> demandParticipant.commitReduction(
                    new ReduceProductionDemandCommand(
                            demand.demandBusinessId(), 7,
                            List.of(new ReduceProductionDemandCommand.TransferAllocation(
                                    demand.productionSourceBusinessId(), 3)),
                            "ORDER_REDUCTION", "订单减量")));
        });

        assertThat(result.requiredQuantity()).isEqualTo(7);
        assertThat(result.transferProducedQuantity()).isEqualTo(3);
        assertThat(demandProjection(demand).get(0)).isEqualTo(7);
        assertThat(jdbc.sql("""
                        SELECT quantity_before, quantity_after, transfer_produced_quantity
                        FROM production_demand_change_facts WHERE demand_business_id = :id
                        """).param("id", demand.demandBusinessId())
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getInt("quantity_before"), resultSet.getInt("quantity_after"),
                        resultSet.getInt("transfer_produced_quantity")))
                .single())
                .containsExactly(10, 7, 3);
    }

    @Test
    @DisplayName("发货失败不留下消费：外层事务回滚后没有消费事实与投影变化")
    void failedDeliveryLeavesNoConsumption() {
        ProductionDemandRef demand = verifiedDemand("ORD-TC-6", 6, 6);
        var beforePhysical = physical(demand);
        var beforeProjection = demandProjection(demand);

        assertThatThrownBy(() -> asAdmin(() -> new TransactionTemplate(transactionManager).execute(status -> {
            var prepared = consumption.prepareConsume(new ConsumeProductionCommand(
                    delivery("DLV-FAIL"), demand.productionSourceBusinessId(), 3));
            consumption.commitConsume(prepared);
            throw new IllegalStateException("发货单据写入失败");
        }))).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM production_consumption_facts")
                .query(Long.class).single()).isZero();
        assertThat(physical(demand)).isEqualTo(beforePhysical);
        assertThat(demandProjection(demand)).isEqualTo(beforeProjection);
    }

    @Test
    @DisplayName("消费数量超过可交付时拒绝，不用 max(0) 截断")
    void consumingMoreThanAvailableIsRejected() {
        ProductionDemandRef demand = verifiedDemand("ORD-TC-7", 5, 5);

        assertThatThrownBy(() -> inTransaction(() -> {
            var prepared = consumption.prepareConsume(new ConsumeProductionCommand(
                    delivery("DLV-7"), demand.productionSourceBusinessId(), 6));
            return consumption.commitConsume(prepared);
        }))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_CONSUME_INSUFFICIENT"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM production_consumption_facts")
                .query(Long.class).single()).isZero();
    }

    private ProductionDemandRef verifiedDemand(String orderItemId, int demandQuantity, int completed) {
        ProductionDemandRef demand = createNewProductDemand(orderItemId, TargetProcess.MAKING, demandQuantity,
                createProduct("商品-" + orderItemId, 20, 10), 4);
        EmployeeRef employee = hireEmployee("张三-" + orderItemId, "MAKING");
        TaskRef task = asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION, new SubjectRef(SubjectType.EMPLOYEE, employee.employeeBusinessId()),
                WORK_DATE, TargetProcess.MAKING,
                List.of(new com.yumi.production.api.TaskItemRequest(
                        demand.productionSourceBusinessId(), demandQuantity)), null, null, null)));
        String itemId = asAdmin(() -> taskService.getTask(task.taskBusinessId()))
                .items().getFirst().taskItemBusinessId();
        asAdmin(() -> verificationService.verify(new VerifyTaskCommand(
                List.of(new TaskItemResultRequest(
                        itemId, demandQuantity - completed, completed, 0, 0, List.of(), null)))));
        return demand;
    }

    /** 在事务内以管理员身份执行：参与接口要求既有事务。 */
    private <T> T inTransaction(java.util.function.Supplier<T> body) {
        return asAdmin(() -> new TransactionTemplate(transactionManager).execute(status -> body.get()));
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

    private List<Integer> demandProjection(ProductionDemandRef demand) {
        return jdbc.sql("""
                        SELECT required_quantity, pending_work_quantity, target_ready_quantity,
                               fulfilled_quantity, fulfillment_gap_quantity
                        FROM production_demand_quantity_projections WHERE demand_business_id = :id
                        """).param("id", demand.demandBusinessId())
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getInt("required_quantity"), resultSet.getInt("pending_work_quantity"),
                        resultSet.getInt("target_ready_quantity"), resultSet.getInt("fulfilled_quantity"),
                        resultSet.getInt("fulfillment_gap_quantity")))
                .single();
    }

    private static ProductionLinkRef delivery(String id) {
        return new ProductionLinkRef("orders", "DELIVERY", id);
    }
}

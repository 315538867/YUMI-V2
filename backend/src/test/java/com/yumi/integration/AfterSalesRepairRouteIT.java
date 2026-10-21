package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.orders.aftersales.api.AfterSalesApplicationService;
import com.yumi.orders.aftersales.api.AfterSalesCaseRef;
import com.yumi.orders.aftersales.api.AfterSalesObligationChangeRoute;
import com.yumi.orders.aftersales.api.AfterSalesObligationRequest;
import com.yumi.orders.aftersales.api.AfterSalesObligationType;
import com.yumi.orders.aftersales.api.AfterSalesReturnService;
import com.yumi.orders.aftersales.api.AfterSalesRouteService;
import com.yumi.orders.aftersales.api.AfterSalesRouteType;
import com.yumi.orders.aftersales.api.ChangeAfterSalesObligationCommand;
import com.yumi.orders.aftersales.api.OpenAfterSalesCaseCommand;
import com.yumi.orders.aftersales.api.ReceiveReturnCommand;
import com.yumi.orders.aftersales.api.RequestRepairCommand;
import com.yumi.orders.api.OrderRef;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.production.api.ArrangeTaskCommand;
import com.yumi.production.api.DefectGroupRequest;
import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.ResponsibilityType;
import com.yumi.production.api.SubjectRef;
import com.yumi.production.api.SubjectType;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.api.TaskApplicationService;
import com.yumi.production.api.TaskItemRequest;
import com.yumi.production.api.TaskItemResultRequest;
import com.yumi.production.api.TaskType;
import com.yumi.production.api.TaskVerificationService;
import com.yumi.production.api.VerifyTaskCommand;
import com.yumi.support.AbstractOrderFixtureTest;
import com.yumi.workforce.api.EmployeeRef;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 售后修复/补发：只转换当前修复缺口，补发同事务建立新制需求但不排班。
 *
 * <p>规则来源：`docs/一期/12-订单模块.md` §13.2 规则 17—18。真实 MySQL 验证：
 * 3 件缺口已转 1 件后最多只能再转 2 件；补发需求不产生任务。
 */
class AfterSalesRepairRouteIT extends AbstractOrderFixtureTest {

    @Autowired
    private AfterSalesApplicationService afterSales;

    @Autowired
    private AfterSalesReturnService afterSalesReturns;

    @Autowired
    private AfterSalesRouteService afterSalesRoutes;

    @Autowired
    private TaskApplicationService productionTasks;

    @Autowired
    private TaskVerificationService productionVerifications;

    @Test
    @DisplayName("修复缺口 3 已转 1 后只能再转 2，补发建立新制需求且不排任务")
    void routeChangeOnlyConvertsCurrentGap() {
        OrderRef order = completedOrder(5);
        String itemId = onlyItemId(order);
        AfterSalesCaseRef caseRef = openRepairCase(order, itemId, 3);
        String obligationId = onlyObligationId(caseRef);
        String receiptItemId = asAdmin(() -> afterSalesReturns.receiveReturn(
                new ReceiveReturnCommand(uniqueKey("AS-RECEIVE"), obligationId, 3, "UNREPAIRED")))
                .receiptItemBusinessId();

        var repair = asAdmin(() -> afterSalesRoutes.requestRepair(
                new RequestRepairCommand(uniqueKey("AS-REPAIR"), obligationId, receiptItemId, 3)));
        String demandId = repair.demandBusinessId();
        String repairSourceId = repair.productionSourceBusinessId();
        assertThat(repairSourceId).isNotBlank();

        EmployeeRef employee = hireEmployee("返修工-" + java.util.UUID.randomUUID(), "MAKING");
        String aux = "AUX-" + java.util.UUID.randomUUID();
        var normalDemand = createNewProductDemand(
                aux, TargetProcess.MAKING, 1, createProduct("辅助商品-" + aux, 20, 10), 4);
        asAdmin(() -> productionTasks.arrange(new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION,
                new SubjectRef(SubjectType.EMPLOYEE, employee.employeeBusinessId()),
                LocalDate.parse("2026-10-07"), TargetProcess.MAKING,
                List.of(new TaskItemRequest(normalDemand.productionSourceBusinessId(), 1)),
                null, null, null)));
        var task = asAdmin(() -> productionTasks.arrange(new ArrangeTaskCommand(
                TaskType.REPAIR, new SubjectRef(SubjectType.EMPLOYEE, employee.employeeBusinessId()),
                LocalDate.parse("2026-10-07"), TargetProcess.MAKING,
                List.of(new TaskItemRequest(repairSourceId, 3)), null, null, null)));
        String taskItemId = asAdmin(() -> productionTasks.getTask(task.taskBusinessId()))
                .items().getFirst().taskItemBusinessId();
        asAdmin(() -> productionVerifications.verify(new VerifyTaskCommand(List.of(
                new TaskItemResultRequest(
                        taskItemId, 0, 0, 0, 3,
                        List.of(new DefectGroupRequest(
                                "OTHER", "修复失败", 3, 0,
                                List.of(new DefectGroupRequest.ScrapPartRequest(
                                        3, PhysicalDestination.DESTROYED,
                                        ResponsibilityType.WAIVED, null, "修复失败报废")))),
                        null)))));
        assertThat(demandProjection(demandId).getLast()).as("修复终止缺口").isEqualTo(3);

        var first = asAdmin(() -> afterSalesRoutes.changeObligation(
                new ChangeAfterSalesObligationCommand(
                        uniqueKey("AS-CHANGE"), obligationId, 1,
                        AfterSalesObligationChangeRoute.REPLACEMENT, null, "客户选择补发 1 件")));
        assertThat(first.successorObligationBusinessId()).isNotBlank();
        assertThat(demandProjection(demandId).getLast()).isEqualTo(2);
        assertThat(obligationTarget(obligationId)).isEqualTo(2);
        String replacementDemand = asAdmin(() -> orderJdbc.sql("""
                        SELECT production_demand_business_id FROM after_sales_production_links
                        WHERE obligation_business_id = :id AND link_type = 'REPLACEMENT'
                        """).param("id", first.successorObligationBusinessId())
                .query(String.class).single());
        assertThat(orderJdbc.sql("""
                        SELECT demand_type FROM production_demands WHERE demand_business_id = :id
                        """).param("id", replacementDemand).query(String.class).single())
                .isEqualTo("NEW_PRODUCT");
        assertThat(countTasksOfDemand(replacementDemand)).isZero();

        assertThatThrownBy(() -> asAdmin(() -> afterSalesRoutes.changeObligation(
                new ChangeAfterSalesObligationCommand(
                        uniqueKey("AS-CHANGE"), obligationId, 3,
                        AfterSalesObligationChangeRoute.REPLACEMENT, null, "超过当前缺口"))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("AFTER_SALES_REPAIR_ROUTE_CHANGE_BLOCKED"));
        assertThat(demandProjection(demandId).getLast()).isEqualTo(2);

        var second = asAdmin(() -> afterSalesRoutes.changeObligation(
                new ChangeAfterSalesObligationCommand(
                        uniqueKey("AS-CHANGE"), obligationId, 2,
                        AfterSalesObligationChangeRoute.REPLACEMENT, null, "剩余 2 件转补发")));
        assertThat(second.successorObligationBusinessId()).isNotBlank();
        assertThat(demandProjection(demandId).getLast()).isZero();
        assertThat(demandProjection(demandId).getFirst()).isZero();
        assertThat(obligationStatus(obligationId)).isEqualTo("VOIDED");
        assertThat(countTasksOfDemand(demandId)).isEqualTo(1);
    }

    @Test
    @DisplayName("返修需求沿用退回制品原始物理根，不伪造历史制作量")
    void repairDemandKeepsOriginalPhysicalRoot() {
        OrderRef order = completedOrder(4);
        String itemId = onlyItemId(order);
        String orderSourceId = orderSourceId(order);
        AfterSalesCaseRef caseRef = openRepairCase(order, itemId, 2);
        String obligationId = onlyObligationId(caseRef);
        String receiptItemId = asAdmin(() -> afterSalesReturns.receiveReturn(
                new ReceiveReturnCommand(uniqueKey("AS-RECEIVE"), obligationId, 2, "UNREPAIRED")))
                .receiptItemBusinessId();

        var repair = asAdmin(() -> afterSalesRoutes.requestRepair(
                new RequestRepairCommand(uniqueKey("AS-REPAIR"), obligationId, receiptItemId, 2)));

        String originalRoot = orderJdbc.sql("""
                        SELECT physical_root_source_business_id FROM production_sources
                        WHERE production_source_business_id = :id
                        """).param("id", orderSourceId).query(String.class).single();
        assertThat(orderJdbc.sql("""
                        SELECT physical_root_source_business_id FROM production_sources
                        WHERE production_source_business_id = :id
                        """).param("id", repair.productionSourceBusinessId())
                .query(String.class).single())
                .isEqualTo(originalRoot);
        assertThat(orderJdbc.sql("""
                        SELECT cumulative_making_created_quantity FROM production_physical_quantity_projections
                        WHERE physical_root_source_business_id = :id
                        """).param("id", originalRoot).query(Integer.class).single())
                .as("修复不增加累计制作量")
                .isEqualTo(4);
    }

    private AfterSalesCaseRef openRepairCase(OrderRef order, String itemId, int quantity) {
        return asAdmin(() -> afterSales.open(new OpenAfterSalesCaseCommand(
                uniqueKey("AS-OPEN"), order.orderBusinessId(), "缝边开裂",
                AfterSalesRouteType.RETURN_AND_REWORK,
                List.of(new AfterSalesObligationRequest(
                        AfterSalesObligationType.RETURN_AND_REPAIR, quantity, null,
                        LocalDate.parse("2026-10-20"), "返修", itemId)))));
    }

    private String onlyItemId(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT order_item_business_id FROM orders_items WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(String.class).single();
    }

    private String onlyObligationId(AfterSalesCaseRef caseRef) {
        return orderJdbc.sql("""
                        SELECT obligation_business_id FROM after_sales_obligations
                        WHERE case_business_id = :id
                        """).param("id", caseRef.caseBusinessId()).query(String.class).single();
    }

    private List<Integer> demandProjection(String demandId) {
        return orderJdbc.sql("""
                        SELECT required_quantity, pending_work_quantity, in_process_quantity,
                               target_ready_quantity, fulfilled_quantity, fulfillment_gap_quantity
                        FROM production_demand_quantity_projections WHERE demand_business_id = :id
                        """).param("id", demandId)
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getInt(1), resultSet.getInt(2), resultSet.getInt(3),
                        resultSet.getInt(4), resultSet.getInt(5), resultSet.getInt(6)))
                .single();
    }

    private int obligationTarget(String obligationId) {
        return orderJdbc.sql("""
                        SELECT target_quantity FROM after_sales_obligations
                        WHERE obligation_business_id = :id
                        """).param("id", obligationId).query(Integer.class).single();
    }

    private String obligationStatus(String obligationId) {
        return orderJdbc.sql("""
                        SELECT status FROM after_sales_obligations WHERE obligation_business_id = :id
                        """).param("id", obligationId).query(String.class).single();
    }

    private long countTasksOfDemand(String demandId) {
        return orderJdbc.sql("""
                        SELECT COUNT(*) FROM production_task_items WHERE demand_business_id = :id
                        """).param("id", demandId).query(Long.class).single();
    }
}

package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.yumi.orders.aftersales.api.AfterSalesApplicationService;
import com.yumi.orders.aftersales.api.AfterSalesCancellationRef;
import com.yumi.orders.aftersales.api.AfterSalesCaseRef;
import com.yumi.orders.aftersales.api.AfterSalesObligationChangeService;
import com.yumi.orders.aftersales.api.AfterSalesObligationRequest;
import com.yumi.orders.aftersales.api.AfterSalesObligationType;
import com.yumi.orders.aftersales.api.AfterSalesReturnService;
import com.yumi.orders.aftersales.api.AfterSalesRouteService;
import com.yumi.orders.aftersales.api.AfterSalesRouteType;
import com.yumi.orders.aftersales.api.AfterSalesCostService;
import com.yumi.orders.aftersales.api.AfterSalesReportingFeed;
import com.yumi.orders.aftersales.api.CancelAfterSalesObligationCommand;
import com.yumi.orders.aftersales.api.ConfirmAfterSalesCostCommand;
import com.yumi.orders.aftersales.api.OrderAfterSalesCostSourceFeed;
import com.yumi.orders.aftersales.api.OpenAfterSalesCaseCommand;
import com.yumi.orders.aftersales.api.ReceiveReturnCommand;
import com.yumi.orders.aftersales.api.RequestRepairCommand;
import com.yumi.orders.api.OrderRef;
import com.yumi.production.api.ArrangeTaskCommand;
import com.yumi.production.api.DefectGroupRequest;
import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.ResponsibilityType;
import com.yumi.production.api.ReturnDispositionType;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.math.BigDecimal;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 取消售后义务的分支核对：池内可用新增处置，已路由终态只解决缺口。
 *
 * <p>规则来源：`docs/一期/12-订单模块.md` §13.2.1。真实 MySQL 验证：
 * 10 件中 3 件修复路线报废移交、7 件池内，取消只新增 7 件处置，物理退出总量为 10。
 */
class AfterSalesCancellationIT extends AbstractOrderFixtureTest {

    @Autowired
    private AfterSalesApplicationService afterSales;

    @Autowired
    private AfterSalesReturnService afterSalesReturns;

    @Autowired
    private AfterSalesRouteService afterSalesRoutes;

    @Autowired
    private AfterSalesObligationChangeService obligationChanges;

    @Autowired
    private TaskApplicationService productionTasks;

    @Autowired
    private TaskVerificationService productionVerifications;

    @Autowired
    private AfterSalesCostService afterSalesCosts;

    @Autowired
    private OrderAfterSalesCostSourceFeed afterSalesCostFeed;

    @Autowired
    private AfterSalesReportingFeed afterSalesReportingFeed;

    @Test
    @DisplayName("10 件中 3 已移交、7 池内的取消只新增 7 件处置，旧终态不重复退出")
    void cancellationOnlyDisposesPoolAvailable() {
        OrderRef order = completedOrder(10);
        String itemId = onlyItemId(order);
        String root = physicalRootOf(order);
        AfterSalesCaseRef caseRef = asAdmin(() -> afterSales.open(new OpenAfterSalesCaseCommand(
                uniqueKey("AS-OPEN"), order.orderBusinessId(), "批量缝边开裂",
                AfterSalesRouteType.RETURN_AND_REWORK,
                List.of(new AfterSalesObligationRequest(
                        AfterSalesObligationType.RETURN_AND_REPAIR, 10, null,
                        LocalDate.parse("2026-10-25"), "返修", itemId)))));
        String obligationId = orderJdbc.sql("""
                        SELECT obligation_business_id FROM after_sales_obligations
                        WHERE case_business_id = :id
                        """).param("id", caseRef.caseBusinessId()).query(String.class).single();
        String receiptItemId = asAdmin(() -> afterSalesReturns.receiveReturn(
                new ReceiveReturnCommand(uniqueKey("AS-RECEIVE"), obligationId, 10, "UNREPAIRED")))
                .receiptItemBusinessId();
        var repair = asAdmin(() -> afterSalesRoutes.requestRepair(
                new RequestRepairCommand(uniqueKey("AS-REPAIR"), obligationId, receiptItemId, 3)));

        EmployeeRef employee = hireEmployee("取消返修工-" + java.util.UUID.randomUUID(), "MAKING");
        String aux = "AUX-" + java.util.UUID.randomUUID();
        var normalDemand = createNewProductDemand(
                aux, TargetProcess.MAKING, 1, createProduct("取消辅助-" + aux, 20, 10), 4);
        asAdmin(() -> productionTasks.arrange(new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION,
                new SubjectRef(SubjectType.EMPLOYEE, employee.employeeBusinessId()),
                LocalDate.parse("2026-10-08"), TargetProcess.MAKING,
                List.of(new TaskItemRequest(normalDemand.productionSourceBusinessId(), 1)),
                null, null, null)));
        var task = asAdmin(() -> productionTasks.arrange(new ArrangeTaskCommand(
                TaskType.REPAIR, new SubjectRef(SubjectType.EMPLOYEE, employee.employeeBusinessId()),
                LocalDate.parse("2026-10-08"), TargetProcess.MAKING,
                List.of(new TaskItemRequest(repair.productionSourceBusinessId(), 3)),
                null, null, null)));
        String taskItemId = asAdmin(() -> productionTasks.getTask(task.taskBusinessId()))
                .items().getFirst().taskItemBusinessId();
        asAdmin(() -> productionVerifications.verify(new VerifyTaskCommand(List.of(
                new TaskItemResultRequest(
                        taskItemId, 0, 0, 0, 3,
                        List.of(new DefectGroupRequest(
                                "OTHER", "修复失败", 3, 0,
                                List.of(new DefectGroupRequest.ScrapPartRequest(
                                        3, PhysicalDestination.TRANSFERRED_OFFLINE_INVENTORY,
                                        ResponsibilityType.WAIVED, null, "修复失败转线下",
                                        "瑕疵品仓", null, "张三", "李四",
                                        LocalDate.parse("2026-10-06"))))),
                        null)))));

        AfterSalesCancellationRef cancelled = asAdmin(() -> obligationChanges.cancel(
                new CancelAfterSalesObligationCommand(
                        uniqueKey("AS-CANCEL"), obligationId,
                        ReturnDispositionType.RELEASE_FROM_OBLIGATION,
                        PhysicalDestination.TRANSFERRED_OFFLINE_INVENTORY,
                        null, null, null,
                        "售后瑕疵仓", "义务取消转线下", "张三", "李四",
                        LocalDateTime.parse("2026-10-16T10:00:00"),
                        LocalDate.parse("2026-10-16"), "客户取消返修义务")));

        assertThat(cancelled.voided()).isTrue();
        assertThat(obligationStatus(obligationId)).isEqualTo("VOIDED");
        assertThat(poolQuantity(receiptItemId)).containsExactly(10, 0, 3, 7);
        assertThat(physical(root)).containsExactly(10, 0, 0, 0, 10);

        assertThat(count("after_sales_return_disposition_facts")).isEqualTo(1);
        assertThat(orderJdbc.sql("""
                        SELECT SUM(quantity) FROM after_sales_return_disposition_facts
                        """).query(Integer.class).single()).isEqualTo(7);
        assertThat(count("production_returned_product_disposition_facts")).isEqualTo(1);
        assertThat(count("production_returned_pool_transfers")).isEqualTo(1);
        // 修复报废退出 3 + 池释放退出 7 = 10，不是 13
        assertThat(count("production_terminal_exits")).isEqualTo(2);
        assertThat(orderJdbc.sql("SELECT SUM(quantity) FROM production_terminal_exits")
                .query(Integer.class).single()).isEqualTo(10);
        // 修复报废转线下写生产移交；池释放走池移交表
        assertThat(count("production_offline_inventory_transfers")).isEqualTo(1);
        assertThat(orderJdbc.sql("""
                        SELECT reason_code FROM production_offline_inventory_transfers
                        """).query(String.class).single()).isEqualTo("QUALITY_SCRAP");
        assertThat(repairDemandState(repair.demandBusinessId())).containsExactly(0, 0, 0, 0, 0);

        // 再次取消不新增处置或退出
        asAdmin(() -> obligationChanges.cancel(new CancelAfterSalesObligationCommand(
                uniqueKey("AS-CANCEL"), obligationId,
                ReturnDispositionType.RELEASE_FROM_OBLIGATION,
                PhysicalDestination.TRANSFERRED_OFFLINE_INVENTORY,
                null, null, null, "售后瑕疵仓", "重复取消", "张三", "李四",
                LocalDateTime.parse("2026-10-16T10:00:00"),
                LocalDate.parse("2026-10-16"), "重复取消")));
        assertThat(count("after_sales_return_disposition_facts")).isEqualTo(1);
        assertThat(count("production_terminal_exits")).isEqualTo(2);

        // 费用来源分页稳定：每次一页，不重复、不跳号
        for (String amount : List.of("10.00", "20.00", "30.00")) {
            asAdmin(() -> afterSalesCosts.confirmCost(new ConfirmAfterSalesCostCommand(
                    uniqueKey("AS-COST"), caseRef.caseBusinessId(), "LABOR",
                    SettlementMoney.of(new BigDecimal(amount), CurrencyCode.CNY),
                    LocalDate.parse("2026-10-16"), "售后人工")));
        }
        var firstPage = afterSalesCostFeed.listAfter(null, 2);
        assertThat(firstPage.items()).hasSize(2);
        assertThat(firstPage.nextCursor()).isNotNull();
        var secondPage = afterSalesCostFeed.listAfter(firstPage.nextCursor(), 2);
        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.nextCursor()).isNull();
        assertThat(firstPage.items().getLast().feedSequence())
                .isLessThan(secondPage.items().getFirst().feedSequence());

        var casePage = afterSalesReportingFeed.changedSince(null, 10);
        assertThat(casePage.items()).singleElement()
                .satisfies(item -> assertThat(item.caseBusinessId())
                        .isEqualTo(caseRef.caseBusinessId()));
        assertThat(casePage.nextCursor()).isNull();
    }

    private String onlyItemId(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT order_item_business_id FROM orders_items WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(String.class).single();
    }

    private String physicalRootOf(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT physical_root_source_business_id FROM production_sources
                        WHERE production_source_business_id = :id
                        """).param("id", orderSourceId(order)).query(String.class).single();
    }

    private String obligationStatus(String obligationId) {
        return orderJdbc.sql("""
                        SELECT status FROM after_sales_obligations WHERE obligation_business_id = :id
                        """).param("id", obligationId).query(String.class).single();
    }

    private List<Integer> poolQuantity(String receiptItemId) {
        return orderJdbc.sql("""
                        SELECT s.original_quantity, s.available_quantity,
                               s.routed_repair_quantity, s.disposed_quantity
                        FROM production_returned_product_sources s
                        JOIN after_sales_return_receipt_items i
                          ON i.production_returned_source_business_id = s.returned_source_business_id
                        WHERE i.receipt_item_business_id = :id
                        """).param("id", receiptItemId)
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getInt(1), resultSet.getInt(2),
                        resultSet.getInt(3), resultSet.getInt(4)))
                .single();
    }

    private List<Integer> physical(String root) {
        return orderJdbc.sql("""
                        SELECT cumulative_making_created_quantity, in_system_quantity, consumed_quantity,
                               destroyed_quantity, offline_inventory_quantity
                        FROM production_physical_quantity_projections
                        WHERE physical_root_source_business_id = :id
                        """).param("id", root)
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getInt(1), resultSet.getInt(2), resultSet.getInt(3),
                        resultSet.getInt(4), resultSet.getInt(5)))
                .single();
    }

    private List<Integer> repairDemandState(String demandId) {
        return orderJdbc.sql("""
                        SELECT required_quantity, pending_work_quantity, in_process_quantity,
                               target_ready_quantity, fulfillment_gap_quantity
                        FROM production_demand_quantity_projections WHERE demand_business_id = :id
                        """).param("id", demandId)
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getInt(1), resultSet.getInt(2), resultSet.getInt(3),
                        resultSet.getInt(4), resultSet.getInt(5)))
                .single();
    }

    private long count(String table) {
        return orderJdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}

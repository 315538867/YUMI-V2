package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.orders.aftersales.api.AfterSalesApplicationService;
import com.yumi.orders.aftersales.api.AfterSalesCaseRef;
import com.yumi.orders.aftersales.api.AfterSalesObligationRequest;
import com.yumi.orders.aftersales.api.AfterSalesObligationType;
import com.yumi.orders.aftersales.api.AfterSalesReturnService;
import com.yumi.orders.aftersales.api.AfterSalesRouteType;
import com.yumi.orders.aftersales.api.DisposeReturnedProductCommand;
import com.yumi.orders.aftersales.api.OpenAfterSalesCaseCommand;
import com.yumi.orders.aftersales.api.ReceiveReturnCommand;
import com.yumi.orders.aftersales.api.ReturnDispositionRef;
import com.yumi.orders.api.OrderRef;
import com.yumi.platform.api.http.ApiException;
import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.ResponsibilityType;
import com.yumi.production.api.ReturnDispositionType;
import com.yumi.support.AbstractOrderFixtureTest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 池内直接报废责任：只能归责到同物理根有效历史作业证据，事实不伪造任务或劳动。
 *
 * <p>规则来源：`docs/一期/12-订单模块.md` §13.3、`docs/一期/11-生产作业模块.md` §7.12。
 */
class ReturnedPoolResponsibilityIT extends AbstractOrderFixtureTest {

    @Autowired
    private AfterSalesApplicationService afterSales;

    @Autowired
    private AfterSalesReturnService afterSalesReturns;

    @Test
    @DisplayName("非法归责证据拒绝，合法员工归责与转线下各只退出一次")
    void scrappedPoolRequiresEvidenceAndExitsOnce() {
        OrderRef order = completedOrder(3);
        String itemId = onlyItemId(order);
        AfterSalesCaseRef caseRef = openReturnCase(order, itemId, 2);
        String obligationId = onlyObligationId(caseRef);
        String receiptItemId = asAdmin(() -> afterSalesReturns.receiveReturn(
                new ReceiveReturnCommand(uniqueKey("AS-RECEIVE"), obligationId, 2, "UNREPAIRED")))
                .receiptItemBusinessId();
        String returnedSourceId = returnedSourceOf(receiptItemId);
        String root = physicalRootOf(order);
        long laborSourcesBeforeDisposal = count("production_labor_sources");
        String actualWorker = orderJdbc.sql("""
                        SELECT subject_business_id FROM production_execution_facts
                        WHERE physical_root_source_business_id = :root AND subject_type = 'EMPLOYEE'
                        ORDER BY occurred_at DESC LIMIT 1
                        """).param("root", root).query(String.class).single();

        String invalidWorker = hireEmployee(
                "无关工人-" + java.util.UUID.randomUUID(), "MAKING").employeeBusinessId();
        assertThatThrownBy(() -> dispose(receiptItemId, 1, ReturnDispositionType.SCRAPPED,
                PhysicalDestination.DESTROYED, ResponsibilityType.EMPLOYEE, invalidWorker, null))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_RESPONSIBILITY_EVIDENCE_REQUIRED"));
        assertThat(count("production_returned_product_disposition_facts")).isZero();
        assertThat(count("after_sales_return_disposition_facts")).isZero();

        var destroyed = dispose(receiptItemId, 1, ReturnDispositionType.SCRAPPED,
                PhysicalDestination.DESTROYED, ResponsibilityType.EMPLOYEE, actualWorker, null);
        assertThat(destroyed.productionTerminalExitBusinessId()).isNotBlank();
        assertThat(count("production_responsibility_facts")).isEqualTo(1);
        assertThat(count("production_returned_pool_transfers")).isZero();
        assertThat(poolQuantity(returnedSourceId, "disposed")).isEqualTo(1);

        var offline = dispose(receiptItemId, 1, ReturnDispositionType.SCRAPPED,
                PhysicalDestination.TRANSFERRED_OFFLINE_INVENTORY,
                ResponsibilityType.WAIVED, null, "客户同意瑕疵品处理");
        assertThat(offline.productionOfflineTransferFactBusinessId()).isNotBlank();
        assertThat(count("production_returned_pool_transfers")).isEqualTo(1);
        // 只使用池移交，不混入生产来源移交表
        assertThat(count("production_offline_inventory_transfers")).isZero();
        assertThat(poolQuantity(returnedSourceId, "disposed")).isEqualTo(2);
        assertThat(poolQuantity(returnedSourceId, "available")).isZero();
        assertThat(physical(root)).containsExactly(3, 0, 1, 1, 1);

        // 不伪造任务、劳动或材料来源
        assertThat(orderJdbc.sql("""
                        SELECT COUNT(*) FROM production_task_items WHERE production_source_business_id = :id
                        """).param("id", returnedSourceId).query(Long.class).single())
                .isZero();
        assertThat(count("production_labor_sources")).isEqualTo(laborSourcesBeforeDisposal);
    }

    @Test
    @DisplayName("非报废义务释放不能销毁，也不能携带扣款责任")
    void releaseMustBeOfflineWithoutResponsibility() {
        OrderRef order = completedOrder(2);
        String itemId = onlyItemId(order);
        AfterSalesCaseRef caseRef = openReturnCase(order, itemId, 1);
        String obligationId = onlyObligationId(caseRef);
        String receiptItemId = asAdmin(() -> afterSalesReturns.receiveReturn(
                new ReceiveReturnCommand(uniqueKey("AS-RECEIVE"), obligationId, 1, "UNREPAIRED")))
                .receiptItemBusinessId();

        assertThatThrownBy(() -> dispose(receiptItemId, 1,
                ReturnDispositionType.RELEASE_FROM_OBLIGATION, PhysicalDestination.DESTROYED,
                null, null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("AFTER_SALES_RETURN_DISPOSITION_REQUIRED"));

        var released = dispose(receiptItemId, 1,
                ReturnDispositionType.RELEASE_FROM_OBLIGATION,
                PhysicalDestination.TRANSFERRED_OFFLINE_INVENTORY,
                null, null, null);
        assertThat(released.productionTerminalExitBusinessId()).isNotBlank();
        assertThat(released.productionOfflineTransferFactBusinessId()).isNotBlank();
        assertThat(count("production_responsibility_facts")).isZero();
    }

    private ReturnDispositionRef dispose(
            String receiptItemId, int quantity, ReturnDispositionType type,
            PhysicalDestination destination, ResponsibilityType responsibilityType,
            String responsibleSubjectId, String waivedReason) {
        return asAdmin(() -> afterSales.disposeReturnedProduct(new DisposeReturnedProductCommand(
                uniqueKey("AS-DISPOSE"), receiptItemId, quantity, type, destination,
                LocalDate.parse("2026-10-15"), "退回制品池处置",
                responsibilityType, responsibleSubjectId, waivedReason,
                destination == PhysicalDestination.TRANSFERRED_OFFLINE_INVENTORY ? "瑕疵品仓" : null,
                null,
                destination == PhysicalDestination.TRANSFERRED_OFFLINE_INVENTORY ? "张三" : null,
                destination == PhysicalDestination.TRANSFERRED_OFFLINE_INVENTORY ? "李四" : null,
                destination == PhysicalDestination.TRANSFERRED_OFFLINE_INVENTORY
                        ? LocalDateTime.parse("2026-10-15T10:00:00") : null)));
    }

    private AfterSalesCaseRef openReturnCase(OrderRef order, String itemId, int quantity) {
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

    private String returnedSourceOf(String receiptItemId) {
        return orderJdbc.sql("""
                        SELECT production_returned_source_business_id
                        FROM after_sales_return_receipt_items
                        WHERE receipt_item_business_id = :id
                        """).param("id", receiptItemId).query(String.class).single();
    }

    private String physicalRootOf(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT physical_root_source_business_id FROM production_sources
                        WHERE production_source_business_id = :id
                        """).param("id", orderSourceId(order)).query(String.class).single();
    }

    private int poolQuantity(String returnedSourceId, String column) {
        return orderJdbc.sql("SELECT " + column + "_quantity FROM production_returned_product_sources"
                        + " WHERE returned_source_business_id = :id")
                .param("id", returnedSourceId).query(Integer.class).single();
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

    private long count(String table) {
        return orderJdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}

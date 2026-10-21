package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.orders.aftersales.api.AfterSalesApplicationService;
import com.yumi.orders.aftersales.api.AfterSalesCaseRef;
import com.yumi.orders.aftersales.api.AfterSalesCaseStatus;
import com.yumi.orders.aftersales.api.AfterSalesCaseView;
import com.yumi.orders.aftersales.api.AfterSalesObligationRequest;
import com.yumi.orders.aftersales.api.AfterSalesObligationStatus;
import com.yumi.orders.aftersales.api.AfterSalesObligationType;
import com.yumi.orders.aftersales.api.AfterSalesQuery;
import com.yumi.orders.aftersales.api.AfterSalesReturnService;
import com.yumi.orders.aftersales.api.AfterSalesRouteType;
import com.yumi.orders.aftersales.api.CompleteAfterSalesCaseCommand;
import com.yumi.orders.aftersales.api.OpenAfterSalesCaseCommand;
import com.yumi.orders.aftersales.api.ReceiveReturnCommand;
import com.yumi.orders.aftersales.api.ReceiveReturnRef;
import com.yumi.orders.aftersales.api.ReturnToCustomerCommand;
import com.yumi.orders.api.OrderRef;
import com.yumi.orders.api.RecipientInfo;
import com.yumi.platform.api.http.ApiException;
import com.yumi.support.AbstractOrderFixtureTest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 最近交付退回、池重入与原样退还。
 *
 * <p>规则来源：`docs/一期/12-订单模块.md` §13.2、`docs/一期/11-生产作业模块.md` §7.11。
 * 真实 MySQL 验证：累计退回不超最近交付、再次退回引用最新交付、重入不增加制作量。
 */
class AfterSalesReturnIT extends AbstractOrderFixtureTest {

    @Autowired
    private AfterSalesApplicationService afterSales;

    @Autowired
    private AfterSalesReturnService afterSalesReturns;

    @Autowired
    private AfterSalesQuery afterSalesQuery;

    @Test
    @DisplayName("退回受最近交付与义务数量双重限制，再次退回引用最新售后交付")
    void returnsFollowLatestDeliveryAndReenterWithoutMaking() {
        OrderRef order = completedOrder(5);
        String itemId = onlyItemId(order);
        AfterSalesCaseRef caseRef = openReturnCase(order, itemId, 5);
        String obligationId = onlyObligationId(caseRef, AfterSalesObligationType.RETURN_AND_REPAIR);
        String sourceId = orderSourceId(order);

        ReceiveReturnRef first = receive(obligationId, 3);
        assertThat(first.quantity()).isEqualTo(3);
        assertThat(first.availablePoolQuantity()).isEqualTo(3);
        assertThat(demandProjection().getFirst()).as("重入不增加制作量").isEqualTo(5);
        assertThat(physical(sourceId)).containsExactly(5, 3, 2, 0, 0);

        assertThatThrownBy(() -> receive(obligationId, 3))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("AFTER_SALES_QUANTITY_EXCEEDED"));

        // 原样退还：池来源消费与售后交付同事务
        var delivered = asAdmin(() -> afterSalesReturns.returnToCustomer(
                new ReturnToCustomerCommand(
                        uniqueKey("AS-RETURN"), first.receiptItemBusinessId(), 3,
                        LocalDateTime.parse("2026-10-10T10:00:00"),
                        new RecipientInfo("张三", "13800000000", "订单收货地址"), "SF-AS-1")));
        assertThat(delivered.quantity()).isEqualTo(3);
        assertThat(physical(sourceId)).containsExactly(5, 0, 5, 0, 0);
        assertThat(poolQuantity(first.productionReturnedSourceBusinessId(), "available")).isZero();
        assertThat(poolQuantity(first.productionReturnedSourceBusinessId(), "disposed")).isEqualTo(3);
        assertThat(count("production_returned_pool_consumption_facts")).isEqualTo(1);

        // 再次退回只能引用最新售后交付
        ReceiveReturnRef second = receive(obligationId, 2);
        assertThat(orderJdbc.sql("""
                        SELECT prior_delivery_type, prior_returned_source_business_id IS NOT NULL
                        FROM after_sales_return_receipt_items
                        WHERE receipt_item_business_id = :id
                        """).param("id", second.receiptItemBusinessId())
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getString(1), resultSet.getBoolean(2)))
                .single())
                .containsExactly("AFTER_SALES_DELIVERY", true);
        assertThat(physical(sourceId)).containsExactly(5, 2, 3, 0, 0);

        assertThatThrownBy(() -> receive(obligationId, 1))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("AFTER_SALES_QUANTITY_EXCEEDED"));

        // 全部处理后义务完成，可以结案
        asAdmin(() -> afterSalesReturns.returnToCustomer(new ReturnToCustomerCommand(
                uniqueKey("AS-RETURN"), second.receiptItemBusinessId(), 2,
                LocalDateTime.parse("2026-10-12T10:00:00"),
                new RecipientInfo("张三", "13800000000", "订单收货地址"), "SF-AS-2")));
        AfterSalesCaseView beforeComplete = asAdmin(
                () -> afterSalesQuery.get(caseRef.caseBusinessId()));
        assertThat(beforeComplete.obligations().getFirst().status())
                .isEqualTo(AfterSalesObligationStatus.FULFILLED);

        asAdmin(() -> afterSales.complete(
                new CompleteAfterSalesCaseCommand(uniqueKey("AS-COMPLETE"),
                        caseRef.caseBusinessId())));
        AfterSalesCaseView completed = asAdmin(
                () -> afterSalesQuery.get(caseRef.caseBusinessId()));
        assertThat(completed.status()).isEqualTo(AfterSalesCaseStatus.COMPLETED);
        assertThat(completed.completedAt()).isNotNull();
    }

    @Test
    @DisplayName("没有可追溯交付时拒绝退回，不留下接收事实")
    void returnWithoutDeliveryIsRejected() {
        // 直接构造一个没有发货的已完成订单：先完成订单再删除其发货，模拟无交付追溯
        OrderRef order = completedOrder(2);
        String itemId = onlyItemId(order);
        AfterSalesCaseRef caseRef = openReturnCase(order, itemId, 2);
        String obligationId = onlyObligationId(caseRef, AfterSalesObligationType.RETURN_AND_REPAIR);
        orderJdbc.sql("UPDATE orders_shipments SET is_valid = 0").update();

        assertThatThrownBy(() -> receive(obligationId, 1))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("AFTER_SALES_RETURN_TRACE_REQUIRED"));
        assertThat(count("after_sales_return_receipt_facts")).isZero();
        assertThat(count("after_sales_return_receipt_items")).isZero();
    }

    private AfterSalesCaseRef openReturnCase(OrderRef order, String itemId, int quantity) {
        return asAdmin(() -> afterSales.open(new OpenAfterSalesCaseCommand(
                uniqueKey("AS-OPEN"), order.orderBusinessId(), "缝边开裂",
                AfterSalesRouteType.RETURN_AND_REWORK,
                List.of(new AfterSalesObligationRequest(
                        AfterSalesObligationType.RETURN_AND_REPAIR, quantity, null,
                        LocalDate.parse("2026-10-20"), "返修", itemId)))));
    }

    private ReceiveReturnRef receive(String obligationId, int quantity) {
        return asAdmin(() -> afterSalesReturns.receiveReturn(new ReceiveReturnCommand(
                uniqueKey("AS-RECEIVE"), obligationId, quantity, "UNREPAIRED")));
    }

    private String onlyItemId(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT order_item_business_id FROM orders_items WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(String.class).single();
    }

    private String onlyObligationId(AfterSalesCaseRef caseRef, AfterSalesObligationType type) {
        return orderJdbc.sql("""
                        SELECT obligation_business_id FROM after_sales_obligations
                        WHERE case_business_id = :id AND obligation_type = :type
                        """).param("id", caseRef.caseBusinessId()).param("type", type.name())
                .query(String.class).single();
    }

    private List<Integer> physical(String sourceId) {
        return orderJdbc.sql("""
                        SELECT cumulative_making_created_quantity, in_system_quantity, consumed_quantity,
                               destroyed_quantity, offline_inventory_quantity
                        FROM production_physical_quantity_projections
                        WHERE physical_root_source_business_id = (
                            SELECT physical_root_source_business_id FROM production_sources
                            WHERE production_source_business_id = :id)
                        """).param("id", sourceId)
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getInt(1), resultSet.getInt(2), resultSet.getInt(3),
                        resultSet.getInt(4), resultSet.getInt(5)))
                .single();
    }

    private List<Integer> demandProjection() {
        return orderJdbc.sql("""
                        SELECT required_quantity, pending_work_quantity, in_process_quantity,
                               target_ready_quantity, fulfilled_quantity, fulfillment_gap_quantity
                        FROM production_demand_quantity_projections LIMIT 1
                        """)
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getInt(1), resultSet.getInt(2), resultSet.getInt(3),
                        resultSet.getInt(4), resultSet.getInt(5), resultSet.getInt(6)))
                .single();
    }

    private int poolQuantity(String returnedSourceId, String column) {
        return orderJdbc.sql("SELECT " + column + "_quantity FROM production_returned_product_sources"
                        + " WHERE returned_source_business_id = :id")
                .param("id", returnedSourceId).query(Integer.class).single();
    }

    private long count(String table) {
        return orderJdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}

package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.orders.api.IntakeLegacyOrderCommand;
import com.yumi.orders.api.LegacyOrderItemRequest;
import com.yumi.orders.api.OpeningOrderService;
import com.yumi.orders.api.OrderDeliveryService;
import com.yumi.orders.api.OrderItemRequest;
import com.yumi.orders.api.OrderOrigin;
import com.yumi.orders.api.OrderQuery;
import com.yumi.orders.api.OrderRef;
import com.yumi.orders.api.OrderReportingFeed;
import com.yumi.orders.api.OrderStatus;
import com.yumi.orders.api.RecipientInfo;
import com.yumi.orders.api.ShipmentItemRequest;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.product.api.MaterialSource;
import com.yumi.product.api.SchemeView;
import com.yumi.production.api.InitializeProductionOpeningCommand;
import com.yumi.production.api.OpeningStageCode;
import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.ProductionOpeningApplicationService;
import com.yumi.production.api.ProductionOpeningLine;
import com.yumi.production.api.TargetProcess;
import com.yumi.support.AbstractOrderFixtureTest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 已有订单接管：只写当前数量基线，完成生产期初初始化后可继续正常履约。
 *
 * <p>规则来源：`docs/一期/12-订单模块.md` §8.4、`docs/一期/11-生产作业模块.md` §5.5、§7.9。
 * 真实 MySQL 验证：接管不生成历史任务、劳动、材料成本或责任来源；一次性初始化。
 */
class OrderOpeningIT extends AbstractOrderFixtureTest {

    @Autowired
    private OpeningOrderService openingOrders;

    @Autowired
    private ProductionOpeningApplicationService productionOpening;

    @Autowired
    private OrderDeliveryService deliveries;

    @Autowired
    private OrderQuery orderQuery;

    @Autowired
    private OrderReportingFeed orderReportingFeed;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("接管已有订单写入明确数量基线，不生成历史任务、劳动、材料或责任来源")
    void intakeWritesOnlyCurrentBaseline() {
        OrderRef order = intake(uniqueKey("OPEN"), 10, fiveStateLines());

        assertThat(asAdmin(() -> orderQuery.get(order.orderBusinessId())).origin())
                .isEqualTo(OrderOrigin.LEGACY_INTAKE);
        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.totalQuantity()).isEqualTo(10);
        assertThat(order.totalAmount().value()).isEqualByComparingTo("880.00");

        assertThat(count("orders_legacy_intakes")).isEqualTo(1);
        assertThat(count("orders_legacy_intake_items")).isEqualTo(1);
        assertThat(count("orders_legacy_state_lines")).isEqualTo(5);
        assertThat(countDemandsOfOrder(order.orderBusinessId())).isEqualTo(1);

        String sourceId = onlySourceId(order);
        assertThat(orderJdbc.sql("""
                        SELECT opening_status, status FROM production_sources
                        WHERE production_source_business_id = :id
                        """).param("id", sourceId)
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getString(1), resultSet.getString(2)))
                .single())
                .containsExactly("OPENED", "ACTIVE");
        assertThat(count("production_opening_facts")).isEqualTo(5);
        assertThat(demandProjection(order)).containsExactly(10, 3, 2, 3, 0, 2);
        assertThat(physical(sourceId)).containsExactly(10, 8, 0, 1, 1);

        // 历史事实不补造
        assertThat(count("production_tasks")).isZero();
        assertThat(count("production_execution_facts")).isZero();
        assertThat(count("production_labor_sources")).isZero();
        assertThat(count("production_material_cost_sources")).isZero();
        assertThat(count("production_responsibility_facts")).isZero();
        assertThat(count("workforce_activity_reservations")).isZero();
        assertThat(count("production_employee_day_loads")).isZero();

        // 目标工序已就绪的数量可以正常继续发货
        var shipment = asAdmin(() -> deliveries.confirmShipment(
                new com.yumi.orders.api.ConfirmShipmentCommand(
                        uniqueKey("SHIP"), order.orderBusinessId(),
                        List.of(new ShipmentItemRequest(onlyGroupId(order), sourceId, 3)),
                        "SF-LEGACY", LocalDateTime.parse("2026-10-09T09:00:00"))));
        assertThat(shipment.shippedQuantity()).isEqualTo(3);
        assertThat(shipment.totalShippedQuantity()).isEqualTo(3);
        assertThat(shipment.deliveryFulfilled()).isFalse();
    }

    @Test
    @DisplayName("同一接管命令重试复用原订单，不重复建需求或期初事实")
    void idempotentIntakeRetryReusesOrder() {
        String key = uniqueKey("OPEN-IDEM");
        OrderRef first = intake(key, 4, List.of(
                new ProductionOpeningLine(OpeningStageCode.NOT_STARTED, null, null, 2, null),
                new ProductionOpeningLine(OpeningStageCode.SEAM_CUTTING,
                        PhysicalDestination.IN_SYSTEM, null, 2, null)));
        OrderRef second = intake(key, 4, List.of(
                new ProductionOpeningLine(OpeningStageCode.NOT_STARTED, null, null, 2, null),
                new ProductionOpeningLine(OpeningStageCode.SEAM_CUTTING,
                        PhysicalDestination.IN_SYSTEM, null, 2, null)));

        assertThat(second.orderBusinessId()).isEqualTo(first.orderBusinessId());
        assertThat(countOrders()).isEqualTo(1);
        assertThat(count("orders_legacy_intakes")).isEqualTo(1);
        assertThat(count("production_opening_facts")).isEqualTo(2);
    }

    @Test
    @DisplayName("状态合计不等于接管数量时拒绝，不留下订单")
    void mismatchedOpeningQuantityIsRejected() {
        assertThatThrownBy(() -> intake(uniqueKey("OPEN-BAD"), 5, List.of(
                new ProductionOpeningLine(OpeningStageCode.NOT_STARTED, null, null, 3, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("合计");
        assertThat(countOrders()).isZero();
        assertThat(count("production_demands")).isZero();
    }

    @Test
    @DisplayName("同一生产来源只能完成一次期初初始化")
    void openingCanOnlyRunOnce() {
        OrderRef order = intake(uniqueKey("OPEN-ONCE"), 3, List.of(
                new ProductionOpeningLine(OpeningStageCode.SEAM_CUTTING,
                        PhysicalDestination.IN_SYSTEM, null, 3, null)));
        String demandId = orderJdbc.sql("""
                        SELECT demand_business_id FROM production_demands WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(String.class).single();

        assertThatThrownBy(() -> asAdmin(() -> new TransactionTemplate(transactionManager)
                .execute(status -> productionOpening.initialize(
                        new InitializeProductionOpeningCommand(demandId, List.of(
                                new ProductionOpeningLine(OpeningStageCode.SEAM_CUTTING,
                                        PhysicalDestination.IN_SYSTEM, null, 3, null)))))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_OPENING_ALREADY_DONE"));
        assertThat(count("production_opening_facts")).isEqualTo(1);
    }

    @Test
    @DisplayName("订单列表与变化 Feed 可读取接管订单")
    void intakeIsVisibleInQueries() {
        OrderRef order = intake(uniqueKey("OPEN-QUERY"), 2, List.of(
                new ProductionOpeningLine(OpeningStageCode.NOT_STARTED, null, null, 2, null)));

        var summaries = asAdmin(() -> orderQuery.search(order.orderNo(), null, 1, 10));
        assertThat(summaries).singleElement().satisfies(summary -> {
            assertThat(summary.orderBusinessId()).isEqualTo(order.orderBusinessId());
            assertThat(summary.origin()).isEqualTo(OrderOrigin.LEGACY_INTAKE);
            assertThat(summary.netReceipts().value()).isEqualByComparingTo("0.00");
        });

        var page = orderReportingFeed.changedSince(null, 10);
        assertThat(page.items()).singleElement()
                .satisfies(item -> assertThat(item.orderBusinessId())
                        .isEqualTo(order.orderBusinessId()));
        assertThat(page.nextCursor()).isNull();

        var first = orderReportingFeed.changedSince(null, 1);
        assertThat(first.nextCursor()).isNotNull();
        assertThat(orderReportingFeed.changedSince(first.nextCursor(), 10).items()).isEmpty();
    }

    private OrderRef intake(String key, int quantity, List<ProductionOpeningLine> lines) {
        OrderItemRequest agreement = new OrderItemRequest(
                defaultProduct.productBusinessId(), standardSchemeSnapshotRef(),
                SchemeView.ProcessCode.SEAM_CUTTING, quantity,
                SettlementMoney.of(new BigDecimal("88.00"), CurrencyCode.CNY),
                new OrderItemRequest.MaterialSourceAgreement(
                        MaterialSource.STUDIO, MaterialSource.STUDIO,
                        MaterialSource.STUDIO, MaterialSource.STUDIO),
                new RecipientInfo("张三", "13800000000", "接管收货地址"));
        return asAdmin(() -> openingOrders.intake(new IntakeLegacyOrderCommand(
                key, defaultCustomer.customerBusinessId(), "EXT-ORDER-001", ORDER_DATE,
                ORDER_DATE.plusDays(10), "上线接管",
                List.of(new LegacyOrderItemRequest(agreement, lines)))));
    }

    private static List<ProductionOpeningLine> fiveStateLines() {
        return List.of(
                new ProductionOpeningLine(OpeningStageCode.NOT_STARTED, null, null, 3, null),
                new ProductionOpeningLine(OpeningStageCode.MAKING,
                        PhysicalDestination.IN_SYSTEM, null, 2, null),
                new ProductionOpeningLine(OpeningStageCode.SEAM_CUTTING,
                        PhysicalDestination.IN_SYSTEM, null, 3, null),
                new ProductionOpeningLine(OpeningStageCode.SCRAPPED,
                        PhysicalDestination.DESTROYED, null, 1, "历史报废已销毁"),
                new ProductionOpeningLine(OpeningStageCode.SCRAPPED,
                        PhysicalDestination.TRANSFERRED_OFFLINE_INVENTORY, null, 1, "历史报废转线下"));
    }

    private long count(String table) {
        return orderJdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private String onlyGroupId(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT g.agreement_group_business_id FROM orders_agreement_groups g
                        JOIN orders_items i ON i.order_item_business_id = g.order_item_business_id
                        WHERE i.order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(String.class).single();
    }

    private String onlySourceId(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT s.production_source_business_id FROM production_sources s
                        JOIN production_demands d ON d.demand_business_id = s.demand_business_id
                        WHERE d.order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(String.class).single();
    }

    private List<Integer> demandProjection(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT required_quantity, pending_work_quantity, in_process_quantity,
                               target_ready_quantity, fulfilled_quantity, fulfillment_gap_quantity
                        FROM production_demand_quantity_projections
                        WHERE demand_business_id = (
                            SELECT demand_business_id FROM production_demands
                            WHERE order_business_id = :id)
                        """).param("id", order.orderBusinessId())
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getInt(1), resultSet.getInt(2), resultSet.getInt(3),
                        resultSet.getInt(4), resultSet.getInt(5), resultSet.getInt(6)))
                .single();
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
}

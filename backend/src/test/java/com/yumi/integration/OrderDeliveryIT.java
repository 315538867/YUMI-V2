package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.customer.api.CustomerQuery;
import com.yumi.customer.api.UpdateCustomerCommand;
import com.yumi.orders.api.ConfirmShipmentCommand;
import com.yumi.orders.api.CorrectShipmentCommand;
import com.yumi.orders.api.CreateConfirmedOrderCommand;
import com.yumi.orders.api.OrderDeliveryService;
import com.yumi.orders.api.OrderItemRequest;
import com.yumi.orders.api.OrderQuery;
import com.yumi.orders.api.OrderRef;
import com.yumi.orders.api.OrderStatus;
import com.yumi.orders.api.OrderView;
import com.yumi.orders.api.RecipientInfo;
import com.yumi.orders.api.ShipmentItemRequest;
import com.yumi.orders.api.ShipmentRef;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.product.api.MaterialSource;
import com.yumi.product.api.SchemeView;
import com.yumi.production.api.ArrangeTaskCommand;
import com.yumi.production.api.SubjectRef;
import com.yumi.production.api.SubjectType;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.api.TaskApplicationService;
import com.yumi.production.api.TaskItemRequest;
import com.yumi.production.api.TaskItemResultRequest;
import com.yumi.production.api.TaskRef;
import com.yumi.production.api.TaskType;
import com.yumi.production.api.TaskVerificationService;
import com.yumi.production.api.TransferProductionToOfflineCommand;
import com.yumi.production.api.TransferReason;
import com.yumi.production.api.VerifyTaskCommand;
import com.yumi.production.api.ProductionOfflineInventoryTransferParticipant;
import com.yumi.support.AbstractOrderFixtureTest;
import com.yumi.workforce.api.EmployeeRef;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 发货、消费引用、更正及交付履约投影。
 *
 * <p>规则来源：`docs/一期/12-订单模块.md` §7.3、§8.3、§10。真实 MySQL 验证：
 * 并发发货不超可用、反向失败不改发货、历史收货信息不回读客户现值。
 */
class OrderDeliveryIT extends AbstractOrderFixtureTest {

    private static final LocalDate WORK_DATE = LocalDate.parse("2026-10-06");
    private static final LocalDateTime SHIPPED_AT = LocalDateTime.parse("2026-10-08T10:30:00");

    @Autowired
    private OrderDeliveryService deliveries;

    @Autowired
    private OrderQuery orderQuery;

    @Autowired
    private CustomerQuery customerQuery;

    @Autowired
    private TaskApplicationService taskService;

    @Autowired
    private TaskVerificationService verificationService;

    @Autowired
    private ProductionOfflineInventoryTransferParticipant transfer;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("发货写消费引用与履约投影，累计满足义务后交付履约成立")
    void shippingConsumesSourceAndUpdatesProjection() {
        OrderRef order = confirmForMaking(6);
        String groupId = onlyGroupId(order);
        String sourceId = onlySourceId(order);
        produce(sourceId, 6);

        ShipmentRef first = ship(order, groupId, sourceId, 4, "SF1001");
        assertThat(first.shippedQuantity()).isEqualTo(4);
        assertThat(first.totalShippedQuantity()).isEqualTo(4);
        assertThat(first.deliveryFulfilled()).isFalse();

        assertThat(consumptionOf(sourceId, "CONSUME")).isEqualTo(4);
        assertThat(physical(sourceId)).containsExactly(6, 2, 4, 0, 0);
        assertThat(projection(order)).containsExactly(6, 4, false);
        assertThat(fulfilledOf(groupId)).isEqualTo(4);
        assertThat(groupStatus(groupId)).isEqualTo("ACTIVE");

        ShipmentRef second = ship(order, groupId, sourceId, 2, "SF1002");
        assertThat(second.totalShippedQuantity()).isEqualTo(6);
        assertThat(second.deliveryFulfilled()).isTrue();
        assertThat(projection(order)).containsExactly(6, 6, true);
        assertThat(groupStatus(groupId)).isEqualTo("FULFILLED");
        assertThat(consumptionOf(sourceId, "CONSUME")).isEqualTo(6);

        OrderView view = asAdmin(() -> orderQuery.get(order.orderBusinessId()));
        assertThat(view.status()).isEqualTo(OrderStatus.IN_FULFILLMENT);
        assertThat(view.fulfillment().deliveryFulfilled()).isTrue();
        assertThat(view.fulfillment().blockingReasons()).containsExactly("PAYMENT_PENDING");
    }

    @Test
    @DisplayName("发货保存确认时的收货信息，客户后续改地址不回写历史发货")
    void shipmentKeepsFrozenRecipient() {
        OrderRef order = confirmForMaking(3);
        String groupId = onlyGroupId(order);
        String sourceId = onlySourceId(order);
        produce(sourceId, 3);

        var before = asAdmin(() -> customerQuery.getCurrent(defaultCustomer.customerBusinessId()));
        asAdmin(() -> customers.update(new UpdateCustomerCommand(
                defaultCustomer.customerBusinessId(), before.name(), before.contactName(),
                before.phone(), "客户新默认地址", before.rowVersion())));

        ship(order, groupId, sourceId, 3, "SF2001");

        String recipient = orderJdbc.sql("""
                        SELECT recipient_payload_json FROM orders_shipments
                        WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(String.class).single();
        assertThat(recipient).contains("订单收货地址").doesNotContain("客户新默认地址");
        assertThat(orderJdbc.sql("""
                        SELECT shipped_at FROM orders_shipments WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId())
                .query(LocalDateTime.class).single())
                .isEqualTo(SHIPPED_AT);
    }

    @Test
    @DisplayName("同一命令重试复用原发货，不重复消费")
    void idempotentRetryReusesShipment() {
        OrderRef order = confirmForMaking(5);
        String groupId = onlyGroupId(order);
        String sourceId = onlySourceId(order);
        produce(sourceId, 5);
        ConfirmShipmentCommand command = command(order, groupId, sourceId, 5);

        ShipmentRef first = asAdmin(() -> deliveries.confirmShipment(command));
        ShipmentRef second = asAdmin(() -> deliveries.confirmShipment(command));

        assertThat(second.shipmentBusinessId()).isEqualTo(first.shipmentBusinessId());
        assertThat(second.shipmentNo()).isEqualTo(first.shipmentNo());
        assertThat(orderJdbc.sql("SELECT COUNT(*) FROM orders_shipments")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(consumptionOf(sourceId, "CONSUME")).isEqualTo(5);
    }

    @Test
    @DisplayName("并发发货只成功一笔，不超出来源可用数量")
    void concurrentShipmentsCannotExceedAvailable() throws Exception {
        OrderRef order = confirmForMaking(8);
        String groupId = onlyGroupId(order);
        String sourceId = onlySourceId(order);
        produce(sourceId, 6);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> first = attempt(start, command(order, groupId, sourceId, 4, "A"));
            Callable<Object> second = attempt(start, command(order, groupId, sourceId, 4, "B"));
            Future<Object> firstResult = executor.submit(first);
            Future<Object> secondResult = executor.submit(second);
            start.countDown();
            List<Object> results = List.of(
                    firstResult.get(15, TimeUnit.SECONDS), secondResult.get(15, TimeUnit.SECONDS));

            assertThat(results.stream().filter(ShipmentRef.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(ApiException.class::isInstance))
                    .singleElement()
                    .satisfies(exception -> assertThat(((ApiException) exception).code())
                            .isEqualTo("PRODUCTION_CONSUME_INSUFFICIENT"));
        } finally {
            executor.shutdownNow();
        }

        assertThat(orderJdbc.sql("SELECT COUNT(*) FROM orders_shipments")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(consumptionOf(sourceId, "CONSUME")).isEqualTo(4);
        assertThat(projection(order)).containsExactly(8, 4, false);
    }

    @Test
    @DisplayName("发货更正按原记录反向消费，投影与组履约同步回退")
    void correctionReversesConsumptionAndProjection() {
        OrderRef order = confirmForMaking(6);
        String groupId = onlyGroupId(order);
        String sourceId = onlySourceId(order);
        produce(sourceId, 6);
        ship(order, groupId, sourceId, 5, "SF3001");

        String shipmentId = onlyShipmentId(order);
        String shipmentItemId = onlyShipmentItemId(order);
        ShipmentRef corrected = asAdmin(() -> deliveries.correctShipment(new CorrectShipmentCommand(
                uniqueKey("CORR-1"), shipmentId, shipmentItemId, 2, "客户少收两件")));

        assertThat(corrected.shippedQuantity()).isEqualTo(-2);
        assertThat(corrected.totalShippedQuantity()).isEqualTo(3);
        assertThat(itemQuantity(shipmentItemId)).isEqualTo(3);
        assertThat(fulfilledOf(groupId)).isEqualTo(3);
        assertThat(projection(order)).containsExactly(6, 3, false);
        assertThat(netConsumedOf(sourceId)).isEqualTo(3);
        assertThat(consumptionOf(sourceId, "REVERSE")).isEqualTo(2);
        assertThat(orderJdbc.sql("""
                        SELECT quantity_delta FROM orders_shipment_correction_facts
                        WHERE shipment_item_business_id = :id
                        """).param("id", shipmentItemId).query(Integer.class).single())
                .isEqualTo(-2);
    }

    @Test
    @DisplayName("更正撤回整笔发货后明细为零，投影回到未交付")
    void correctionCanWithdrawWholeShipment() {
        OrderRef order = confirmForMaking(4);
        String groupId = onlyGroupId(order);
        String sourceId = onlySourceId(order);
        produce(sourceId, 4);
        ship(order, groupId, sourceId, 4, "SF4001");

        String shipmentId = onlyShipmentId(order);
        String shipmentItemId = onlyShipmentItemId(order);
        asAdmin(() -> deliveries.correctShipment(new CorrectShipmentCommand(
                uniqueKey("CORR-2"), shipmentId, shipmentItemId, 4, "整笔退回")));

        assertThat(itemQuantity(shipmentItemId)).isZero();
        assertThat(fulfilledOf(groupId)).isZero();
        assertThat(groupStatus(groupId)).isEqualTo("ACTIVE");
        assertThat(projection(order)).containsExactly(4, 0, false);
        assertThat(netConsumedOf(sourceId)).isZero();
    }

    @Test
    @DisplayName("生产反向失败时发货与投影保持原样")
    void failedReverseLeavesShipmentUnchanged() {
        OrderRef order = confirmForMaking(6);
        String groupId = onlyGroupId(order);
        String sourceId = onlySourceId(order);
        produce(sourceId, 6);
        inTransaction(() -> {
            var command = new TransferProductionToOfflineCommand(
                    sourceId, 1, TransferReason.QUALITY_SCRAP, "PRODUCTION-TRANSFER",
                    "瑕疵品仓", "1 件瑕疵转线下", "张三", "李四",
                    LocalDateTime.parse("2026-10-07T09:00:00"));
            var prepared = transfer.prepareTransfer(command);
            return transfer.commit(command, prepared);
        });
        ship(order, groupId, sourceId, 2, "SF5001");

        String shipmentItemId = onlyShipmentItemId(order);
        assertThatThrownBy(() -> asAdmin(() -> deliveries.correctShipment(new CorrectShipmentCommand(
                uniqueKey("CORR-3"), onlyShipmentId(order), shipmentItemId, 1, "尝试减少"))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_TRANSFER_IRREVERSIBLE"));

        assertThat(itemQuantity(shipmentItemId)).isEqualTo(2);
        assertThat(consumptionOf(sourceId, "CONSUME")).isEqualTo(2);
        assertThat(consumptionOf(sourceId, "REVERSE")).isZero();
        assertThat(orderJdbc.sql("SELECT COUNT(*) FROM orders_shipment_correction_facts")
                .query(Long.class).single()).isZero();
        assertThat(orderJdbc.sql("""
                        SELECT shipped_quantity FROM orders_fulfillment_projections
                        WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(Integer.class).single())
                .isEqualTo(2);
    }

    private OrderRef confirmForMaking(int quantity) {
        CreateConfirmedOrderCommand command = new CreateConfirmedOrderCommand(
                uniqueKey("ORDER"), defaultCustomer.customerBusinessId(), null, ORDER_DATE,
                ORDER_DATE.plusDays(7),
                null,
List.of(new OrderItemRequest(
                        defaultProduct.productBusinessId(), standardSchemeSnapshotRef(),
                        SchemeView.ProcessCode.MAKING, quantity,
                        SettlementMoney.of(new BigDecimal("88.00"), CurrencyCode.CNY),
                        new OrderItemRequest.MaterialSourceAgreement(
                                MaterialSource.STUDIO, MaterialSource.STUDIO,
                                MaterialSource.STUDIO, MaterialSource.STUDIO),
                        new RecipientInfo("张三", "13800000000", "订单收货地址"))));
        return asAdmin(() -> orders.createAndConfirm(command));
    }

    private void produce(String sourceId, int quantity) {
        EmployeeRef employee = hireEmployee("发货工人-" + java.util.UUID.randomUUID(), "MAKING");
        TaskRef task = asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION,
                new SubjectRef(SubjectType.EMPLOYEE, employee.employeeBusinessId()),
                WORK_DATE, TargetProcess.MAKING,
                List.of(new TaskItemRequest(sourceId, quantity)), null, null, null)));
        String itemId = asAdmin(() -> taskService.getTask(task.taskBusinessId()))
                .items().getFirst().taskItemBusinessId();
        asAdmin(() -> verificationService.verify(new VerifyTaskCommand(
                List.of(new TaskItemResultRequest(itemId, 0, quantity, 0, 0, List.of(), null)))));
    }

    private ShipmentRef ship(OrderRef order, String groupId, String sourceId, int quantity, String trackingNo) {
        return asAdmin(() -> deliveries.confirmShipment(
                command(order, groupId, sourceId, quantity, trackingNo)));
    }

    private ConfirmShipmentCommand command(
            OrderRef order, String groupId, String sourceId, int quantity) {
        return command(order, groupId, sourceId, quantity, "SF-" + java.util.UUID.randomUUID());
    }

    private ConfirmShipmentCommand command(
            OrderRef order, String groupId, String sourceId, int quantity, String trackingNo) {
        return new ConfirmShipmentCommand(
                uniqueKey("SHIP"), order.orderBusinessId(),
                List.of(new ShipmentItemRequest(groupId, sourceId, quantity)), trackingNo, SHIPPED_AT);
    }

    private Callable<Object> attempt(CountDownLatch start, ConfirmShipmentCommand command) {
        return () -> {
            start.await();
            try {
                return asAdmin(() -> deliveries.confirmShipment(command));
            } catch (ApiException exception) {
                return exception;
            }
        };
    }

    private <T> T inTransaction(java.util.function.Supplier<T> body) {
        return asAdmin(() -> new TransactionTemplate(transactionManager).execute(status -> body.get()));
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

    private int consumptionOf(String sourceId, String effectType) {
        return orderJdbc.sql("""
                        SELECT COALESCE(SUM(quantity), 0) FROM production_consumption_facts
                        WHERE production_source_business_id = :id AND is_valid = 1
                          AND effect_type = :type
                        """).param("id", sourceId).param("type", effectType)
                .query(Integer.class).single();
    }

    private int netConsumedOf(String sourceId) {
        return orderJdbc.sql("""
                        SELECT COALESCE(SUM(
                                   CASE WHEN effect_type = 'CONSUME' THEN quantity ELSE -quantity END), 0)
                        FROM production_consumption_facts
                        WHERE production_source_business_id = :id AND is_valid = 1
                        """).param("id", sourceId).query(Integer.class).single();
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

    private List<Object> projection(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT required_quantity, shipped_quantity, delivery_fulfilled
                        FROM orders_fulfillment_projections WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId())
                .query((resultSet, rowNumber) -> List.<Object>of(
                        resultSet.getInt(1), resultSet.getInt(2), resultSet.getBoolean(3)))
                .single();
    }

    private int fulfilledOf(String groupId) {
        return orderJdbc.sql("""
                        SELECT fulfilled_quantity FROM orders_agreement_groups
                        WHERE agreement_group_business_id = :id
                        """).param("id", groupId).query(Integer.class).single();
    }

    private String groupStatus(String groupId) {
        return orderJdbc.sql("""
                        SELECT status FROM orders_agreement_groups WHERE agreement_group_business_id = :id
                        """).param("id", groupId).query(String.class).single();
    }

    private String onlyShipmentId(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT shipment_business_id FROM orders_shipments WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(String.class).single();
    }

    private String onlyShipmentItemId(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT i.shipment_item_business_id FROM orders_shipment_items i
                        JOIN orders_shipments s ON s.shipment_business_id = i.shipment_business_id
                        WHERE s.order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(String.class).single();
    }

    private int itemQuantity(String shipmentItemId) {
        return orderJdbc.sql("""
                        SELECT quantity FROM orders_shipment_items WHERE shipment_item_business_id = :id
                        """).param("id", shipmentItemId).query(Integer.class).single();
    }
}

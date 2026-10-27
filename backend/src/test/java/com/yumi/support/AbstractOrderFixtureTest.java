package com.yumi.support;

import com.yumi.customer.api.CreateCustomerCommand;
import com.yumi.customer.api.CustomerApplicationService;
import com.yumi.customer.api.CustomerRef;
import com.yumi.orders.api.CreateConfirmedOrderCommand;
import com.yumi.orders.api.OrderApplicationService;
import com.yumi.orders.api.OrderItemRequest;
import com.yumi.orders.api.OrderRef;
import com.yumi.orders.api.RecipientInfo;
import com.yumi.product.api.MaterialSource;
import com.yumi.product.api.ProductSnapshotService;
import com.yumi.product.api.SchemeType;
import com.yumi.product.api.SchemeView;
import com.yumi.production.api.TargetProcess;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** 订单模块集成测试装配：真实客户、商品、生产需求与幂等。 */
public abstract class AbstractOrderFixtureTest extends AbstractProductionFixtureTest {

    protected static final LocalDate ORDER_DATE = LocalDate.parse("2026-10-05");

    @Autowired
    protected CustomerApplicationService customers;

    @Autowired
    protected OrderApplicationService orders;

    @Autowired
    protected ProductSnapshotService orderProductSnapshots;

    @Autowired
    protected JdbcClient orderJdbc;

    @Autowired
    protected com.yumi.orders.api.OrderDeliveryService orderDeliveries;

    @Autowired
    protected com.yumi.orders.api.OrderPaymentParticipant orderPayments;

    @Autowired
    protected com.yumi.orders.api.OrderCompletionService orderCompletions;

    @Autowired
    protected com.yumi.production.api.TaskApplicationService orderTaskService;

    @Autowired
    protected com.yumi.production.api.TaskVerificationService orderVerificationService;

    protected CustomerRef defaultCustomer;

    @BeforeEach
    void prepareOrders() {
        orderJdbc.sql("DELETE FROM reporting_projection_differences").update();
        orderJdbc.sql("DELETE FROM reporting_projection_version_records").update();
        orderJdbc.sql("DELETE FROM reporting_projection_versions").update();
        orderJdbc.sql("DELETE FROM reporting_projection_active").update();
        orderJdbc.sql("DELETE FROM reporting_projection_records").update();
        orderJdbc.sql("DELETE FROM reporting_feed_cursors").update();
        orderJdbc.sql("DELETE FROM reporting_feed_failures").update();
        orderJdbc.sql("DELETE FROM reporting_feed_leases").update();
        orderJdbc.sql("DELETE FROM after_sales_payment_link_facts").update();
        orderJdbc.sql("DELETE FROM after_sales_cost_source_items").update();
        orderJdbc.sql("DELETE FROM after_sales_obligation_facts").update();
        orderJdbc.sql("DELETE FROM after_sales_delivery_items").update();
        orderJdbc.sql("DELETE FROM after_sales_delivery_facts").update();
        orderJdbc.sql("DELETE FROM after_sales_return_disposition_facts").update();
        orderJdbc.sql("DELETE FROM after_sales_production_links").update();
        orderJdbc.sql("DELETE FROM after_sales_return_receipt_items").update();
        orderJdbc.sql("DELETE FROM after_sales_return_receipt_facts").update();
        orderJdbc.sql("DELETE FROM after_sales_completion_facts").update();
        orderJdbc.sql("DELETE FROM after_sales_obligations").update();
        orderJdbc.sql("DELETE FROM after_sales_issue_facts").update();
        orderJdbc.sql("DELETE FROM after_sales_cases").update();
        orderJdbc.sql("DELETE FROM orders_legacy_state_lines").update();
        orderJdbc.sql("DELETE FROM orders_legacy_intake_items").update();
        orderJdbc.sql("DELETE FROM orders_legacy_intakes").update();
        orderJdbc.sql("DELETE FROM orders_completion_facts").update();
        orderJdbc.sql("DELETE FROM orders_payment_link_facts").update();
        orderJdbc.sql("DELETE FROM orders_refund_obligations").update();
        orderJdbc.sql("DELETE FROM orders_shipment_correction_facts").update();
        orderJdbc.sql("DELETE FROM orders_shipment_source_links").update();
        orderJdbc.sql("DELETE FROM orders_shipment_items").update();
        orderJdbc.sql("DELETE FROM orders_shipments").update();
        orderJdbc.sql("DELETE FROM orders_change_offline_transfer_links").update();
        orderJdbc.sql("DELETE FROM orders_change_actions").update();
        orderJdbc.sql("DELETE FROM orders_change_facts").update();
        orderJdbc.sql("DELETE FROM orders_fulfillment_projections").update();
        orderJdbc.sql("DELETE FROM orders_receivable_facts").update();
        orderJdbc.sql("DELETE FROM orders_agreement_processes").update();
        orderJdbc.sql("DELETE FROM orders_agreement_versions").update();
        orderJdbc.sql("DELETE FROM orders_agreement_groups").update();
        orderJdbc.sql("DELETE FROM orders_items").update();
        orderJdbc.sql("DELETE FROM orders_confirmation_facts").update();
        orderJdbc.sql("DELETE FROM orders_orders").update();
        defaultCustomer = asAdmin(() -> customers.create(
                new CreateCustomerCommand("订单客户", "张三", "13800000000", "上海市某路 1 号")));
    }

    /** 默认订单命令：一个订单项，缝边剪袋交付。 */
    protected CreateConfirmedOrderCommand defaultOrderCommand(String idempotencyKey, int quantity) {
        return new CreateConfirmedOrderCommand(
                idempotencyKey,
                defaultCustomer.customerBusinessId(),
                null,
                ORDER_DATE,
                ORDER_DATE.plusDays(7),
                null,
List.of(new OrderItemRequest(
                        defaultProduct.productBusinessId(),
                        standardSchemeSnapshotRef(),
                        SchemeView.ProcessCode.SEAM_CUTTING,
                        quantity,
                        com.yumi.platform.api.value.SettlementMoney.of(
                                new BigDecimal("88.00"), com.yumi.platform.api.value.CurrencyCode.CNY),
                        new OrderItemRequest.MaterialSourceAgreement(
                                MaterialSource.STUDIO, MaterialSource.STUDIO,
                                MaterialSource.STUDIO, MaterialSource.STUDIO),
                        new RecipientInfo("张三", "13800000000", "上海市某路 1 号"))));
    }

    /** 默认商品内置标准方案的快照引用。 */
    protected com.yumi.product.api.ProductSnapshotRef standardSchemeSnapshotRef() {
        return asAdmin(() -> {
            String schemeId = productSchemes.listSchemes(defaultProduct.productBusinessId()).stream()
                    .filter(scheme -> scheme.schemeType() == SchemeType.SYSTEM_STANDARD)
                    .findFirst()
                    .orElseThrow()
                    .schemeBusinessId();
            return orderProductSnapshots.acquireSchemeSnapshot(schemeId).ref();
        });
    }

    /** 使用调用方给出的幂等键（幂等断言需要复用同一键）。 */
    protected OrderRef confirm(String idempotencyKey, int quantity) {
        return asAdmin(() -> orders.createAndConfirm(defaultOrderCommand(idempotencyKey, quantity)));
    }

    /** 本测试方法内稳定的幂等键（重试用同一键，跨方法互不影响）。 */
    protected static String uniqueKey(String suffix) {
        return "IT-" + java.util.UUID.randomUUID() + "-" + suffix;
    }

    /** 便于断言生产需求是否随订单建立。 */
    protected long countDemandsOfOrder(String orderBusinessId) {
        return orderJdbc.sql("""
                        SELECT COUNT(*) FROM production_demands
                        WHERE order_business_id = :id
                        """).param("id", orderBusinessId).query(Long.class).single();
    }

    protected long countOrders() {
        return orderJdbc.sql("SELECT COUNT(*) FROM orders_orders").query(Long.class).single();
    }

    protected OrderRef confirmOrderForMaking(int quantity) {
        return confirmOrderForMaking(quantity, "张三", "订单收货地址");
    }

    protected OrderRef confirmOrderForMaking(int quantity, String recipientName, String address) {
        CreateConfirmedOrderCommand command = new CreateConfirmedOrderCommand(
                uniqueKey("ORDER"), defaultCustomer.customerBusinessId(), null, ORDER_DATE,
                ORDER_DATE.plusDays(7),
                null,
List.of(new OrderItemRequest(
                        defaultProduct.productBusinessId(), standardSchemeSnapshotRef(),
                        SchemeView.ProcessCode.MAKING, quantity,
                        com.yumi.platform.api.value.SettlementMoney.of(
                                new BigDecimal("88.00"),
                                com.yumi.platform.api.value.CurrencyCode.CNY),
                        new OrderItemRequest.MaterialSourceAgreement(
                                MaterialSource.STUDIO, MaterialSource.STUDIO,
                                MaterialSource.STUDIO, MaterialSource.STUDIO),
                        new RecipientInfo(recipientName, "13800000000", address))));
        return asAdmin(() -> orders.createAndConfirm(command));
    }

    protected String produceAtMaking(String sourceId, int quantity) {
        com.yumi.workforce.api.EmployeeRef employee = hireEmployee(
                "订单工人-" + java.util.UUID.randomUUID(), "MAKING");
        var task = asAdmin(() -> orderTaskService.arrange(
                new com.yumi.production.api.ArrangeTaskCommand(
                        com.yumi.production.api.TaskType.NORMAL_PRODUCTION,
                        new com.yumi.production.api.SubjectRef(
                                com.yumi.production.api.SubjectType.EMPLOYEE,
                                employee.employeeBusinessId()),
                        java.time.LocalDate.parse("2026-10-06"),
                        com.yumi.production.api.TargetProcess.MAKING,
                        List.of(new com.yumi.production.api.TaskItemRequest(sourceId, quantity)),
                        null, null, null)));
        String itemId = asAdmin(() -> orderTaskService.getTask(task.taskBusinessId()))
                .items().getFirst().taskItemBusinessId();
        asAdmin(() -> orderVerificationService.verify(
                new com.yumi.production.api.VerifyTaskCommand(List.of(
                        new com.yumi.production.api.TaskItemResultRequest(
                                itemId, 0, quantity, 0, 0, List.of(), null)))));
        return itemId;
    }

    protected String orderGroupId(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT g.agreement_group_business_id FROM orders_agreement_groups g
                        JOIN orders_items i ON i.order_item_business_id = g.order_item_business_id
                        WHERE i.order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(String.class).single();
    }

    protected String orderSourceId(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT s.production_source_business_id FROM production_sources s
                        JOIN production_demands d ON d.demand_business_id = s.demand_business_id
                        WHERE d.order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(String.class).single();
    }

    protected com.yumi.orders.api.OrderPaymentLinkRef payOrder(
            String orderBusinessId, com.yumi.orders.api.OrderPaymentType type, String amount) {
        return asAdmin(() -> new TransactionTemplate(transactionManager).execute(status -> {
            var prepared = orderPayments.preparePayment(
                    new com.yumi.orders.api.OrderPaymentRequest(
                            orderBusinessId, type,
                            com.yumi.platform.api.value.SettlementMoney.of(
                                    new BigDecimal(amount),
                                    com.yumi.platform.api.value.CurrencyCode.CNY)));
            return orderPayments.commitPaymentLink(
                    new com.yumi.orders.api.CommitOrderPaymentLink(
                            prepared, "CASH-" + java.util.UUID.randomUUID()));
        }));
    }

    protected OrderRef completedOrder(int quantity) {
        OrderRef order = confirmOrderForMaking(quantity);
        String sourceId = orderSourceId(order);
        produceAtMaking(sourceId, quantity);
        String groupId = orderGroupId(order);
        asAdmin(() -> orderDeliveries.confirmShipment(
                new com.yumi.orders.api.ConfirmShipmentCommand(
                        uniqueKey("SHIP"), order.orderBusinessId(),
                        List.of(new com.yumi.orders.api.ShipmentItemRequest(
                                groupId, sourceId, quantity)),
                        "SF-" + java.util.UUID.randomUUID(),
                        java.time.LocalDateTime.parse("2026-10-08T10:00:00"))));
        payOrder(order.orderBusinessId(), com.yumi.orders.api.OrderPaymentType.RECEIPT,
                new BigDecimal("88.00").multiply(BigDecimal.valueOf(quantity)).toPlainString());
        asAdmin(() -> orderCompletions.complete(
                new com.yumi.orders.api.CompleteOrderCommand(
                        uniqueKey("COMPLETE"), order.orderBusinessId())));
        return order;
    }

    /** 目标工序类型（仅供测试断言）。 */
    protected static TargetProcess targetOf(SchemeView.ProcessCode code) {
        return TargetProcess.valueOf(code.name());
    }
}

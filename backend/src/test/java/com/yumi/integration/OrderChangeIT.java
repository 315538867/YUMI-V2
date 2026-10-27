package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.orders.api.ChangeConfirmedOrderCommand;
import com.yumi.orders.api.CreateConfirmedOrderCommand;
import com.yumi.orders.api.OrderChangeAction;
import com.yumi.orders.api.OrderChangeKind;
import com.yumi.orders.api.OrderChangeRef;
import com.yumi.orders.api.OrderChangeService;
import com.yumi.orders.api.OrderItemRequest;
import com.yumi.orders.api.OrderRef;
import com.yumi.orders.api.OrderView;
import com.yumi.orders.api.RecipientInfo;
import com.yumi.platform.api.http.ApiException;
import com.yumi.product.api.MaterialSource;
import com.yumi.product.api.SchemeView;
import com.yumi.production.api.ArrangeTaskCommand;
import com.yumi.production.api.SubjectRef;
import com.yumi.production.api.SubjectType;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.api.TaskApplicationService;
import com.yumi.production.api.TaskItemRequest;
import com.yumi.production.api.TaskType;
import com.yumi.support.AbstractOrderFixtureTest;
import com.yumi.workforce.api.EmployeeRef;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 订单变更：约定版本/继任项、未制取消与已制移交，并同事务释放已有任务预留。
 *
 * <p>规则来源：`docs/一期/12-订单模块.md` §7.2、§10。真实 MySQL 验证：
 * 改变供料不改旧事实、混合订单任务只释放目标来源、继任链完整。
 */
class OrderChangeIT extends AbstractOrderFixtureTest {

    private static final LocalDate WORK_DATE = LocalDate.parse("2026-10-06");

    @Autowired
    private OrderChangeService orderChanges;

    @Autowired
    private TaskApplicationService taskService;

    @Autowired
    private com.yumi.orders.api.OrderQuery orderQuery;

    @Test
    @DisplayName("仅价格与收件人变化只追加约定版本，不改生产需求与既有事实")
    void priceChangeOnlyBumpsAgreementVersion() {
        OrderRef order = confirm(idem("CH-1"), 6);
        String itemId = onlyItemId(order);
        String demandFingerprintBefore = demandFingerprint(order.orderBusinessId());
        String sourceBefore = sourceOfDemand(demandIdOfItem(itemId));

        OrderChangeRef change = asAdmin(() -> orderChanges.change(new ChangeConfirmedOrderCommand(
                idem("CH-1-A"),
                order.orderBusinessId(),
                order.contentVersion(),
                List.of(new OrderChangeAction(
                        OrderChangeKind.PRICE_OR_RECIPIENT, itemId,
                        null,
                        com.yumi.platform.api.value.SettlementMoney.of(
                                new BigDecimal("99.00"), com.yumi.platform.api.value.CurrencyCode.CNY),
                        new RecipientInfo("李四", "13900000000", "北京市某路 2 号"),
                        null, null, null, null)),
                "客户改价并改收货人")));

        assertThat(change.currentReceivableAmount().value()).isEqualByComparingTo("594.00");
        OrderView view = asAdmin(() -> orderQuery.get(order.orderBusinessId()));
        var item = view.items().getFirst();
        assertThat(item.agreement().versionNo()).isEqualTo(2);
        assertThat(item.agreement().unitPrice().value()).isEqualByComparingTo("99.00");
        assertThat(item.agreement().recipient().customerName()).isEqualTo("李四");

        // 旧版本保留、生产需求与来源完全不变
        assertThat(orderJdbc.sql("""
                        SELECT COUNT(*) FROM orders_agreement_versions v
                        JOIN orders_agreement_groups g ON g.agreement_group_business_id = v.group_business_id
                        WHERE g.order_item_business_id = :id
                        """).param("id", itemId).query(Long.class).single()).isEqualTo(2);
        assertThat(demandFingerprint(order.orderBusinessId())).isEqualTo(demandFingerprintBefore);
        assertThat(sourceOfDemand(demandIdOfItem(itemId))).isEqualTo(sourceBefore);
    }

    @Test
    @DisplayName("混合来源任务只释放目标来源，其他来源明细保持原样")
    void reducingOneSourceKeepsOtherSource() {
        CreateConfirmedOrderCommand command = new CreateConfirmedOrderCommand(
                idem("CH-2"), defaultCustomer.customerBusinessId(), null, ORDER_DATE,
                ORDER_DATE.plusDays(5),
                null,
List.of(item(2, TargetProcess.MAKING), item(3, TargetProcess.MAKING)));
        OrderRef order = asAdmin(() -> orders.createAndConfirm(command));
        var items = orderJdbc.sql("""
                        SELECT order_item_business_id FROM orders_items
                        WHERE order_business_id = :id ORDER BY item_no
                        """).param("id", order.orderBusinessId()).query(String.class).list();
        String firstItem = items.get(0);
        String secondItem = items.get(1);
        String firstSource = sourceOfDemand(demandIdOfItem(firstItem));
        String secondSource = sourceOfDemand(demandIdOfItem(secondItem));

        EmployeeRef employee = hireEmployee("张三", "MAKING");
        var task = asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION,
                new SubjectRef(SubjectType.EMPLOYEE, employee.employeeBusinessId()),
                WORK_DATE, TargetProcess.MAKING,
                List.of(new TaskItemRequest(firstSource, 2), new TaskItemRequest(secondSource, 3)),
                null, null, null)));
        assertThat(task.plannedWorkQuantity()).isEqualTo(5);

        OrderChangeRef change = asAdmin(() -> orderChanges.change(new ChangeConfirmedOrderCommand(
                idem("CH-2-A"),
                order.orderBusinessId(),
                order.contentVersion(),
                List.of(new OrderChangeAction(
                        OrderChangeKind.CANCELLATION, firstItem, null, null, null, null, null, null, null)),
                "客户取消第一个订单项")));

        assertThat(change.actionCount()).isEqualTo(1);
        // 只释放目标来源：目标项取消，另一来源明细与数量不变
        assertThat(orderJdbc.sql("""
                        SELECT verification_status FROM production_task_items
                        WHERE production_source_business_id = :id
                        """).param("id", firstSource).query(String.class).single())
                .isEqualTo("CANCELLED");
        assertThat(orderJdbc.sql("""
                        SELECT planned_quantity FROM production_task_items
                        WHERE production_source_business_id = :id
                        """).param("id", secondSource).query(Integer.class).single())
                .isEqualTo(3);
        assertThat(reservedOf(firstSource)).as("目标来源占用释放").isZero();
        assertThat(reservedOf(secondSource)).as("其他来源占用保留").isEqualTo(3);
        assertThat(orderJdbc.sql("""
                        SELECT active_quantity FROM orders_items WHERE order_item_business_id = :id
                        """).param("id", firstItem).query(Integer.class).single()).isZero();
        assertThat(demandRequired(demandIdOfItem(firstItem))).isZero();
    }

    @Test
    @DisplayName("生产定义变化建立继任项与继任需求，原需求等额减少且旧事实保留")
    void productionDefinitionChangeCreatesSuccessor() {
        OrderRef order = confirm(idem("CH-3"), 5);
        String itemId = onlyItemId(order);
        String originalDemand = demandIdOfItem(itemId);
        long versionsBefore = orderJdbc.sql("SELECT COUNT(*) FROM orders_agreement_versions")
                .query(Long.class).single();

        OrderChangeRef change = asAdmin(() -> orderChanges.change(new ChangeConfirmedOrderCommand(
                idem("CH-3-A"),
                order.orderBusinessId(),
                order.contentVersion(),
                List.of(new OrderChangeAction(
                        OrderChangeKind.PRODUCTION_DEFINITION, itemId, null, null, null,
                        standardSchemeSnapshotRef(), SchemeView.ProcessCode.MAKING,
                        new OrderItemRequest.MaterialSourceAgreement(
                                MaterialSource.CUSTOMER, MaterialSource.CUSTOMER,
                                MaterialSource.CUSTOMER, MaterialSource.CUSTOMER),
                        5)),
                "客户改为自带料")));

        assertThat(change.actionCount()).isEqualTo(1);
        assertThat(orderJdbc.sql("""
                        SELECT COUNT(*) FROM orders_items WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(Long.class).single()).isEqualTo(2);
        assertThat(orderJdbc.sql("SELECT COUNT(*) FROM orders_agreement_versions")
                .query(Long.class).single())
                .as("原版本保留并新增继任版本")
                .isEqualTo(versionsBefore + 1);
        assertThat(demandRequired(originalDemand)).as("原需求等额减少").isZero();
        assertThat(orderJdbc.sql("""
                        SELECT COUNT(*) FROM production_demands WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(Long.class).single())
                .isEqualTo(2);
        var successor = orderJdbc.sql("""
                        SELECT production_basis_fingerprint FROM production_demands
                        WHERE order_business_id = :id AND demand_business_id <> :original
                        """).param("id", order.orderBusinessId()).param("original", originalDemand)
                .query(String.class).single();
        assertThat(successor).matches("[0-9a-f]{64}");
        assertThat(orderJdbc.sql("""
                        SELECT COUNT(*) FROM orders_change_actions
                        WHERE change_business_id = :id AND action_type = 'SUCCESSOR'
                          AND successor_order_item_business_id IS NOT NULL
                        """).param("id", change.changeBusinessId()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("取消整项后订单项与约定组为 CANCELLED，应收扣减")
    void cancellationReducesReceivable() {
        OrderRef order = confirm(idem("CH-4"), 4);

        OrderChangeRef change = asAdmin(() -> orderChanges.change(new ChangeConfirmedOrderCommand(
                idem("CH-4-A"), order.orderBusinessId(), order.contentVersion(),
                List.of(new OrderChangeAction(
                        OrderChangeKind.CANCELLATION, onlyItemId(order),
                        null, null, null, null, null, null, null)),
                "整项取消")));

        assertThat(change.currentReceivableAmount().value()).isEqualByComparingTo("0.00");
        assertThat(orderJdbc.sql("""
                        SELECT status FROM orders_agreement_groups
                        """).query(String.class).single()).isEqualTo("CANCELLED");
        assertThat(orderJdbc.sql("SELECT status FROM orders_items")
                .query(String.class).single()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("版本不匹配时并发冲突，不产生变更事实")
    void staleVersionIsRejected() {
        OrderRef order = confirm(idem("CH-5"), 3);
        assertThatThrownBy(() -> asAdmin(() -> orderChanges.change(new ChangeConfirmedOrderCommand(
                idem("CH-5-A"), order.orderBusinessId(), order.contentVersion() + 99,
                List.of(new OrderChangeAction(
                        OrderChangeKind.CANCELLATION, onlyItemId(order),
                        null, null, null, null, null, null, null)),
                "过期版本"))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("ORDER_VERSION_CONFLICT"));
        assertThat(orderJdbc.sql("SELECT COUNT(*) FROM orders_change_facts")
                .query(Long.class).single()).isZero();
    }

    private static String idem(String suffix) {
        return "IT-CHANGE-" + java.util.UUID.randomUUID() + "-" + suffix;
    }

    private OrderItemRequest item(int quantity) {
        return item(quantity, TargetProcess.SEAM_CUTTING);
    }

    private OrderItemRequest item(int quantity, TargetProcess targetProcess) {
        return new OrderItemRequest(
                defaultProduct.productBusinessId(), standardSchemeSnapshotRef(),
                SchemeView.ProcessCode.valueOf(targetProcess.name()), quantity,
                com.yumi.platform.api.value.SettlementMoney.of(
                        new BigDecimal("10.00"), com.yumi.platform.api.value.CurrencyCode.CNY),
                new OrderItemRequest.MaterialSourceAgreement(
                        MaterialSource.STUDIO, MaterialSource.STUDIO,
                        MaterialSource.STUDIO, MaterialSource.STUDIO),
                new RecipientInfo("张三", "13800000000", "地址"));
    }

    private String onlyItemId(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT order_item_business_id FROM orders_items WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(String.class).single();
    }

    private String demandIdOfItem(String itemId) {
        return orderJdbc.sql("""
                        SELECT demand_business_id FROM production_demands WHERE order_item_business_id = :id
                        """).param("id", itemId).query(String.class).single();
    }

    private String sourceOfDemand(String demandId) {
        return orderJdbc.sql("""
                        SELECT production_source_business_id FROM production_sources
                        WHERE demand_business_id = :id
                        """).param("id", demandId).query(String.class).single();
    }

    private String demandFingerprint(String orderId) {
        return orderJdbc.sql("""
                        SELECT production_basis_fingerprint FROM production_demands
                        WHERE order_business_id = :id
                        """).param("id", orderId).query(String.class).single();
    }

    private int demandRequired(String demandId) {
        return orderJdbc.sql("""
                        SELECT required_quantity FROM production_demands WHERE demand_business_id = :id
                        """).param("id", demandId).query(Integer.class).single();
    }

    private int reservedOf(String sourceId) {
        return orderJdbc.sql("""
                        SELECT COALESCE(reserved_quantity, 0) FROM production_source_reservations
                        WHERE production_source_business_id = :id
                        """).param("id", sourceId).query(Integer.class).single();
    }
}

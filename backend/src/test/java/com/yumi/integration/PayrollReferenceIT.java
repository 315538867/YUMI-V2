package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.finance.payroll.api.CalculatePayrollCommand;
import com.yumi.finance.payroll.api.CreatePayrollPeriodCommand;
import com.yumi.finance.payroll.api.EmployeePayrollView;
import com.yumi.finance.payroll.api.PayrollApplicationService;
import com.yumi.finance.payroll.api.PayrollPeriodRef;
import com.yumi.finance.payroll.api.PayrollQuery;
import com.yumi.finance.payroll.api.PayrollSourceIngestionService;
import com.yumi.finance.payroll.api.IngestPayrollSourcesCommand;
import com.yumi.orders.api.CreateConfirmedOrderCommand;
import com.yumi.orders.api.OrderItemRequest;
import com.yumi.orders.api.OrderRef;
import com.yumi.orders.api.RecipientInfo;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.product.api.MaterialSource;
import com.yumi.product.api.SchemeView;
import com.yumi.production.api.CreateProductionDemandRequest;
import com.yumi.production.api.DemandType;
import com.yumi.production.api.ProductionBasis;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.api.TaskItemResultRequest;
import com.yumi.support.AbstractOrderFixtureTest;
import com.yumi.workforce.api.EmployeeRef;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 工资期间与基础参考：逐项参考、客户供料、缺依据区分，不生成应付或自动扣款。
 *
 * <p>规则来源：`docs/一期/13-财务结算模块.md` §5、§8.1。真实 MySQL 验证：
 * 参考行可追溯到劳动/材料来源，客户供料不计工作室成本，缺人工标准标记不完整。
 */
class PayrollReferenceIT extends AbstractOrderFixtureTest {

    @Autowired
    private PayrollSourceIngestionService payrollIngestion;

    @Autowired
    private PayrollApplicationService payroll;

    @Autowired
    private PayrollQuery payrollQuery;


    @Test
    @DisplayName("参考逐项列出劳动与材料；客户供料不计成本，缺依据行保留且不阻止参考")
    void referenceLinesExposeSourcesWithoutAutomaticDeduction() {
        OrderRef studioOrder = completedOrder(2);
        String studioWorker = workerOf(studioOrder);
        String incompleteWorker = createLaborWithoutStandard();
        OrderRef customerOrder = completedCustomerSupplyOrder(2);
        String customerWorker = workerOf(customerOrder);

        asAdmin(() -> payrollIngestion.ingest(
                new IngestPayrollSourcesCommand(uniqueKey("PAYROLL-INGEST"))));
        PayrollPeriodRef period = asAdmin(() -> payroll.createPeriod(
                new CreatePayrollPeriodCommand(
                        uniqueKey("PAYROLL-PERIOD"), "2026-10",
                        LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"))));
        var calculation = asAdmin(() -> payroll.calculate(
                new CalculatePayrollCommand(uniqueKey("PAYROLL-CALC"), period.periodBusinessId())));
        assertThat(calculation.employeeCount()).isGreaterThanOrEqualTo(3);
        assertThat(calculation.referenceLineCount()).isPositive();

        EmployeePayrollView studio = asAdmin(() -> payrollQuery.getEmployeePayroll(
                period.periodBusinessId(), studioWorker));
        assertThat(studio.status()).isEqualTo("REFERENCE_READY");
        assertThat(studio.lines())
                .filteredOn(line -> line.lineType().equals("LABOR"))
                .anySatisfy(line -> {
                    assertThat(line.referenceAmount()).isNotNull();
                    assertThat(line.referenceAmount().value()).isPositive();
                });
        assertThat(studio.lines())
                .filteredOn(line -> line.lineType().equals("MATERIAL"))
                .anySatisfy(line -> {
                    assertThat(line.materialSource()).isEqualTo("STUDIO");
                    assertThat(line.referenceAmount()).isNotNull();
                });
        assertThat(studio.lines())
                .filteredOn(EmployeePayrollView.ReferenceLineView::estimated)
                .isNotEmpty()
                .allSatisfy(line -> {
                    assertThat(line.occurred()).isFalse();
                    assertThat(line.referenceAmount()).isNull();
                    assertThat(line.note()).contains("未发生工序");
                });
        assertThat(studio.confirmedAmount()).as("参考不是应付").isNull();

        EmployeePayrollView incomplete = asAdmin(() -> payrollQuery.getEmployeePayroll(
                period.periodBusinessId(), incompleteWorker));
        assertThat(incomplete.lines())
                .filteredOn(line -> line.lineType().equals("LABOR"))
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.incomplete()).isTrue();
                    assertThat(line.referenceAmount()).isNull();
                    assertThat(line.note()).contains("缺少计件人工标准");
                });

        EmployeePayrollView customer = asAdmin(() -> payrollQuery.getEmployeePayroll(
                period.periodBusinessId(), customerWorker));
        assertThat(customer.lines())
                .filteredOn(line -> line.lineType().equals("MATERIAL"))
                .anySatisfy(line -> {
                    assertThat(line.materialSource()).isEqualTo("CUSTOMER");
                    assertThat(line.referenceAmount()).isNull();
                    assertThat(line.note()).contains("客户供料");
                });
    }

    @Test
    @DisplayName("工资期间不得重叠，重复期间号重试复用原期间")
    void periodsCannotOverlap() {
        PayrollPeriodRef first = asAdmin(() -> payroll.createPeriod(
                new CreatePayrollPeriodCommand(
                        uniqueKey("PAYROLL-PERIOD"), "2026-09",
                        LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"))));
        PayrollPeriodRef replay = asAdmin(() -> payroll.createPeriod(
                new CreatePayrollPeriodCommand(
                        uniqueKey("PAYROLL-PERIOD"), "2026-09",
                        LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"))));
        assertThat(replay.periodBusinessId()).isEqualTo(first.periodBusinessId());

        assertThatThrownBy(() -> asAdmin(() -> payroll.createPeriod(
                new CreatePayrollPeriodCommand(
                        uniqueKey("PAYROLL-PERIOD"), "2026-09B",
                        LocalDate.parse("2026-09-15"), LocalDate.parse("2026-10-15")))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PAYROLL_PERIOD_OVERLAP"));
    }

    /** 建立一条没有人工标准行的生产需求并完成核验，用于验证缺依据参考。 */
    private String createLaborWithoutStandard() {
        var product = createProduct("无人工标准-" + java.util.UUID.randomUUID(), 20, 10);
        String schemeId = asAdmin(() -> standardSchemeId(product));
        var studio = MaterialSource.STUDIO;
        var basis = new ProductionBasis(
                "SSNAP", "PSNAP", "SCH", TargetProcess.MAKING,
                new ProductionBasis.MaterialSources(studio, studio, studio, studio),
                List.of(
                        costLine("GLUE", 10.5000),
                        costLine("COLOR_PASTE", 21.0000)),
                "2026.10-1", "HALF_UP-4",
                com.yumi.platform.api.value.ContentFingerprint.of(sha256("no-labor")),
                4, 5, 4);
        var demand = asAdmin(() -> transactionTemplate.execute(status -> {
            var request = new CreateProductionDemandRequest(
                    "orders", "ORDER_ITEM", "AUX-" + java.util.UUID.randomUUID(), "ORD-AUX",
                    null, DemandType.NEW_PRODUCT, product.productBusinessId(),
                    productSnapshots.acquireProductSnapshot(product.productBusinessId())
                            .snapshotBusinessId(),
                    schemeId,
                    productSnapshots.acquireSchemeSnapshot(schemeId).ref().snapshotBusinessId(),
                    basis, 1, null);
            return demandParticipant.commitCreate(demandParticipant.prepareCreate(request));
        }));
        EmployeeRef employee = hireEmployee(
                "无标准工人-" + java.util.UUID.randomUUID(), "MAKING");
        var task = asAdmin(() -> orderTaskService.arrange(
                new com.yumi.production.api.ArrangeTaskCommand(
                        com.yumi.production.api.TaskType.NORMAL_PRODUCTION,
                        new com.yumi.production.api.SubjectRef(
                                com.yumi.production.api.SubjectType.EMPLOYEE,
                                employee.employeeBusinessId()),
                        LocalDate.parse("2026-10-09"), TargetProcess.MAKING,
                        List.of(new com.yumi.production.api.TaskItemRequest(
                                demand.productionSourceBusinessId(), 1)),
                        null, null, null)));
        String itemId = asAdmin(() -> orderTaskService.getTask(task.taskBusinessId()))
                .items().getFirst().taskItemBusinessId();
        asAdmin(() -> orderVerificationService.verify(
                new com.yumi.production.api.VerifyTaskCommand(List.of(
                        new TaskItemResultRequest(itemId, 0, 1, 0, 0, List.of(), null)))));
        return employee.employeeBusinessId();
    }

    private static ProductionBasis.CostLine costLine(String component, double amount) {
        return new ProductionBasis.CostLine(
                component, TargetProcess.MAKING, "NON_LABOR_MATERIAL", MaterialSource.STUDIO,
                com.yumi.platform.api.value.CostAmount.of(
                        BigDecimal.valueOf(amount), CurrencyCode.CNY));
    }

    private static String sha256(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private OrderRef completedCustomerSupplyOrder(int quantity) {
        CreateConfirmedOrderCommand command = new CreateConfirmedOrderCommand(
                uniqueKey("ORDER"), defaultCustomer.customerBusinessId(), null, ORDER_DATE,
                ORDER_DATE.plusDays(7),
                List.of(new OrderItemRequest(
                        defaultProduct.productBusinessId(), standardSchemeSnapshotRef(),
                        SchemeView.ProcessCode.MAKING, quantity,
                        SettlementMoney.of(new BigDecimal("88.00"), CurrencyCode.CNY),
                        new OrderItemRequest.MaterialSourceAgreement(
                                MaterialSource.CUSTOMER, MaterialSource.CUSTOMER,
                                MaterialSource.CUSTOMER, MaterialSource.CUSTOMER),
                        new RecipientInfo("张三", "13800000000", "订单收货地址"))));
        OrderRef order = asAdmin(() -> orders.createAndConfirm(command));
        String sourceId = orderSourceId(order);
        produceAtMaking(sourceId, quantity);
        asAdmin(() -> orderDeliveries.confirmShipment(
                new com.yumi.orders.api.ConfirmShipmentCommand(
                        uniqueKey("SHIP"), order.orderBusinessId(),
                        List.of(new com.yumi.orders.api.ShipmentItemRequest(
                                orderGroupId(order), sourceId, quantity)),
                        "SF-" + java.util.UUID.randomUUID(),
                        java.time.LocalDateTime.parse("2026-10-08T10:00:00"))));
        payOrder(order.orderBusinessId(), com.yumi.orders.api.OrderPaymentType.RECEIPT,
                new BigDecimal("88.00").multiply(BigDecimal.valueOf(quantity)).toPlainString());
        asAdmin(() -> orderCompletions.complete(
                new com.yumi.orders.api.CompleteOrderCommand(
                        uniqueKey("COMPLETE"), order.orderBusinessId())));
        return order;
    }

    private String workerOf(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT e.subject_business_id FROM production_execution_facts e
                        JOIN production_demands d ON d.demand_business_id = e.demand_business_id
                        WHERE d.order_business_id = :id AND e.subject_type = 'EMPLOYEE' LIMIT 1
                        """).param("id", order.orderBusinessId()).query(String.class).single();
    }
}

package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.finance.payroll.api.CalculatePayrollCommand;
import com.yumi.finance.payroll.api.ConfirmEmployeePayrollCommand;
import com.yumi.finance.payroll.api.CreatePayrollPeriodCommand;
import com.yumi.finance.payroll.api.EmployeePayrollView;
import com.yumi.finance.payroll.api.IngestPayrollSourcesCommand;
import com.yumi.finance.payroll.api.PayrollApplicationService;
import com.yumi.finance.payroll.api.PayrollConfirmationService;
import com.yumi.finance.payroll.api.PayrollPeriodRef;
import com.yumi.finance.payroll.api.PayrollQuery;
import com.yumi.finance.payroll.api.PayrollSourceIngestionService;
import com.yumi.finance.payroll.application.PayrollConfirmationServiceImpl;
import com.yumi.finance.payroll.application.PayrollRepository;
import com.yumi.orders.api.OrderRef;
import com.yumi.platform.api.audit.AuditService;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.idempotency.IdempotentCommandService;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.support.AbstractOrderFixtureTest;
import com.yumi.support.RecordingAuthorization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 逐人工资核定：处理明细不自动抵扣，应付与人工成本原子保存。
 *
 * <p>规则来源：`docs/一期/13-财务结算模块.md` §8.1、§5.1.2。真实 MySQL 验证：
 * 10+20 元处理项配 1000 元工资仍为 1000 元应付与成本；错误引用拒绝；成本失败整体回滚。
 */
class PayrollConfirmationIT extends AbstractOrderFixtureTest {

    @Autowired
    private PayrollSourceIngestionService payrollIngestion;

    @Autowired
    private PayrollApplicationService payroll;

    @Autowired
    private PayrollQuery payrollQuery;

    @Autowired
    private PayrollConfirmationService confirmations;

    @Autowired
    private PayrollRepository payrollRepository;

    @Autowired
    private IdempotentCommandService idempotency;

    @Autowired
    private CurrentActorProvider currentActorProvider;

    @Autowired
    private AuditService audit;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("10+20 元处理项配 1000 元工资仍形成 1000 元应付及人工成本")
    void processingItemsDoNotReduceConfirmedWage() {
        Context context = readyPayroll(2);
        EmployeePayrollView view = context.view();
        String laborLine = view.lines().stream()
                .filter(line -> line.lineType().equals("LABOR"))
                .findFirst().orElseThrow().lineBusinessId();
        String materialLine = view.lines().stream()
                .filter(line -> line.lineType().equals("MATERIAL"))
                .findFirst().orElseThrow().lineBusinessId();

        ConfirmEmployeePayrollCommand command = new ConfirmEmployeePayrollCommand(
                uniqueKey("PAYROLL-CONFIRM"), view.resultBusinessId(),
                view.rowVersion(), view.referenceFingerprint(),
                SettlementMoney.of(new BigDecimal("1000.00"), CurrencyCode.CNY),
                "十月工资",
                List.of(
                        new ConfirmEmployeePayrollCommand.ProcessingItemRequest(
                                laborLine,
                                SettlementMoney.of(new BigDecimal("10.00"), CurrencyCode.CNY),
                                "报废胶水处理"),
                        new ConfirmEmployeePayrollCommand.ProcessingItemRequest(
                                materialLine,
                                SettlementMoney.of(new BigDecimal("20.00"), CurrencyCode.CNY),
                                "材料处理"),
                        new ConfirmEmployeePayrollCommand.ProcessingItemRequest(
                                laborLine, null, "未填金额"),
                        new ConfirmEmployeePayrollCommand.ProcessingItemRequest(
                                laborLine,
                                SettlementMoney.of(BigDecimal.ZERO, CurrencyCode.CNY),
                                "明确为零")));
        var confirmation = asAdmin(() -> confirmations.confirmEmployee(command));

        assertThat(confirmation.payableAmount().value()).isEqualByComparingTo("1000.00");
        assertThat(orderJdbc.sql("""
                        SELECT confirmed_amount FROM finance_payroll_results
                        WHERE result_business_id = :id
                        """).param("id", view.resultBusinessId()).query(BigDecimal.class).single())
                .isEqualByComparingTo("1000.00");
        assertThat(orderJdbc.sql("""
                        SELECT amount FROM finance_direct_labor_costs
                        WHERE result_business_id = :id
                        """).param("id", view.resultBusinessId()).query(BigDecimal.class).single())
                .isEqualByComparingTo("1000.0000");
        assertThat(orderJdbc.sql("""
                        SELECT manual_amount FROM finance_payroll_processing_items
                        WHERE confirmation_business_id = :id ORDER BY created_at, item_business_id
                        """).param("id", confirmation.confirmationBusinessId())
                .query(BigDecimal.class).list())
                .hasSize(4)
                .anySatisfy(amount -> assertThat(amount).isNull())
                .anySatisfy(amount -> assertThat(amount).isEqualByComparingTo("0.00"));

        // 幂等重试不重复核定
        asAdmin(() -> confirmations.confirmEmployee(command));
        assertThat(count("finance_payroll_confirmations")).isEqualTo(1);
        assertThat(count("finance_payroll_processing_items")).isEqualTo(4);
    }

    @Test
    @DisplayName("错误参考行或过期指纹拒绝，内部成本写入失败时核定与应付全部回滚")
    void invalidReferencesAndCostFailureRollBack() {
        Context context = readyPayroll(2);
        EmployeePayrollView view = context.view();
        String laborLine = view.lines().stream()
                .filter(line -> line.lineType().equals("LABOR"))
                .findFirst().orElseThrow().lineBusinessId();

        assertThatThrownBy(() -> asAdmin(() -> confirmations.confirmEmployee(
                new ConfirmEmployeePayrollCommand(
                        uniqueKey("PAYROLL-CONFIRM"), view.resultBusinessId(),
                        view.rowVersion(), view.referenceFingerprint(),
                        SettlementMoney.of(new BigDecimal("100.00"), CurrencyCode.CNY), "错误引用",
                        List.of(new ConfirmEmployeePayrollCommand.ProcessingItemRequest(
                                "PYL-OTHER", null, "其他员工"))))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PAYROLL_REFERENCE_LINE_INVALID"));
        assertThatThrownBy(() -> asAdmin(() -> confirmations.confirmEmployee(
                new ConfirmEmployeePayrollCommand(
                        uniqueKey("PAYROLL-CONFIRM"), view.resultBusinessId(),
                        view.rowVersion(), "a".repeat(64),
                        SettlementMoney.of(new BigDecimal("100.00"), CurrencyCode.CNY), "过期参考",
                        List.of()))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PAYROLL_REFERENCE_STALE"));
        assertThat(count("finance_payroll_confirmations")).isZero();

        // 预置同结果成本来源，使内部成本写入唯一键失败
        orderJdbc.sql("""
                        INSERT INTO finance_direct_labor_costs (
                            cost_source_business_id, result_business_id, amount, business_date, created_at)
                        VALUES ('DLC-PRE', :id, 1.0000, '2026-10-31', UTC_TIMESTAMP(6))
                        """).param("id", view.resultBusinessId()).update();
        assertThatThrownBy(() -> asAdmin(() -> confirmations.confirmEmployee(
                new ConfirmEmployeePayrollCommand(
                        uniqueKey("PAYROLL-CONFIRM"), view.resultBusinessId(),
                        view.rowVersion(), view.referenceFingerprint(),
                        SettlementMoney.of(new BigDecimal("1000.00"), CurrencyCode.CNY), "成本失败",
                        List.of(new ConfirmEmployeePayrollCommand.ProcessingItemRequest(
                                laborLine, null, "处理"))))))
                .isInstanceOf(Exception.class);
        assertThat(count("finance_payroll_confirmations")).isZero();
        assertThat(count("finance_payroll_processing_items")).isZero();
        assertThat(orderJdbc.sql("""
                        SELECT status FROM finance_payroll_results WHERE result_business_id = :id
                        """).param("id", view.resultBusinessId()).query(String.class).single())
                .isEqualTo("REFERENCE_READY");
    }

    @Test
    @DisplayName("无核定权限时拒绝，不写任何核定或成本")
    void confirmationRequiresPermission() {
        Context context = readyPayroll(2);
        EmployeePayrollView view = context.view();
        var denied = new RecordingAuthorization();
        denied.deny(PermissionCodes.PAYROLL_CONFIRM);
        var service = new PayrollConfirmationServiceImpl(
                payrollRepository, idempotency, denied, currentActorProvider, audit, clock,
                transactionManager);

        assertThatThrownBy(() -> asAdmin(() -> service.confirmEmployee(
                new ConfirmEmployeePayrollCommand(
                        uniqueKey("PAYROLL-CONFIRM"), view.resultBusinessId(),
                        view.rowVersion(), view.referenceFingerprint(),
                        SettlementMoney.of(new BigDecimal("100.00"), CurrencyCode.CNY), "无权限",
                        List.of()))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PERMISSION_DENIED"));
        assertThat(count("finance_payroll_confirmations")).isZero();
        assertThat(count("finance_direct_labor_costs")).isZero();
    }

    private Context readyPayroll(int quantity) {
        OrderRef order = completedOrder(quantity);
        asAdmin(() -> payrollIngestion.ingest(
                new IngestPayrollSourcesCommand(uniqueKey("PAYROLL-INGEST"))));
        PayrollPeriodRef period = asAdmin(() -> payroll.createPeriod(
                new CreatePayrollPeriodCommand(
                        uniqueKey("PAYROLL-PERIOD"), "2026-10",
                        LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"))));
        asAdmin(() -> payroll.calculate(
                new CalculatePayrollCommand(uniqueKey("PAYROLL-CALC"), period.periodBusinessId())));
        String worker = orderJdbc.sql("""
                        SELECT e.subject_business_id FROM production_execution_facts e
                        JOIN production_demands d ON d.demand_business_id = e.demand_business_id
                        WHERE d.order_business_id = :id AND e.subject_type = 'EMPLOYEE' LIMIT 1
                        """).param("id", order.orderBusinessId()).query(String.class).single();
        EmployeePayrollView view = asAdmin(() -> payrollQuery.getEmployeePayroll(
                period.periodBusinessId(), worker));
        return new Context(order, period, view);
    }

    private long count(String table) {
        return orderJdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private record Context(OrderRef order, PayrollPeriodRef period, EmployeePayrollView view) {
    }
}

package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.finance.cash.api.CashApplicationService;
import com.yumi.finance.cash.api.DailyCashRecordRef;
import com.yumi.finance.cash.api.RecordDailyCashCommand;
import com.yumi.finance.cash.application.CashApplicationServiceImpl;
import com.yumi.finance.cash.application.CashRepository;
import com.yumi.finance.costledger.api.OperatingExpenseCommand;
import com.yumi.finance.costledger.api.OperatingExpenseService;
import com.yumi.finance.payroll.api.PayrollPaymentParticipant;
import com.yumi.orders.aftersales.api.AfterSalesPaymentParticipant;
import com.yumi.orders.api.OrderPaymentParticipant;
import com.yumi.platform.api.audit.AuditService;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.idempotency.IdempotentCommandService;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.file.FileReferenceParticipant;
import com.yumi.platform.api.numbering.BusinessNumberService;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.support.AbstractOrderFixtureTest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 日常收支与可选经营费用：未计费用不生成成本，收入不记费用，失败全回滚。
 *
 * <p>规则来源：`docs/一期/13-财务结算模块.md` §8.4。真实 MySQL 验证：
 * 业务日期归月，计费用支出与现金同事务。
 */
class DailyCashRecordIT extends AbstractOrderFixtureTest {

    @Autowired
    private CashApplicationService cash;

    @Autowired
    private CashRepository cashRepository;

    @Autowired
    private OrderPaymentParticipant orderPayments;

    @Autowired
    private AfterSalesPaymentParticipant afterSalesPayments;

    @Autowired
    private PayrollPaymentParticipant payrollPayments;

    @Autowired
    private IdempotentCommandService idempotency;

    @Autowired
    private AuthorizationService authorization;

    @Autowired
    private CurrentActorProvider currentActorProvider;

    @Autowired
    private AuditService audit;

    @Autowired
    private BusinessNumberService numbering;

    @Autowired
    private FileReferenceParticipant fileReferences;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("收入与未计费用支出不生成成本，计费用支出与现金同事务，业务日期归月")
    void dailyCashAndOptionalExpense() {
        var income = record("IN", "OTHER_INCOME", "2026-09-30", "100.00", "样品收入", false);
        assertThat(income.cashFact().cashType()).isEqualTo("DAILY_INCOME");
        assertThat(income.cashFact().direction()).isEqualTo("IN");
        assertThat(income.cashFact().businessDate()).isEqualTo(LocalDate.parse("2026-09-30"));
        assertThat(income.costEntryBusinessId()).isNull();
        assertThat(count("finance_operating_expenses")).isZero();

        assertThatThrownBy(() -> record("IN", "OTHER_INCOME", "2026-10-01",
                "10.00", "收入计费用", true))
                .isInstanceOf(IllegalArgumentException.class);

        var plainExpense = record("OUT", "RENT", "2026-10-02", "50.00", "房租", false);
        assertThat(plainExpense.costEntryBusinessId()).isNull();
        assertThat(count("finance_operating_expenses")).isZero();

        RecordDailyCashCommand expenseCommand = new RecordDailyCashCommand(
                uniqueKey("CASH-DAILY"), "OUT", "RENT", LocalDate.parse("2026-10-05"),
                money("80.00"), "房租", true);
        var expense = asAdmin(() -> cash.recordDailyCash(expenseCommand));
        assertThat(expense.costEntryBusinessId()).isNotBlank();
        assertThat(count("finance_operating_expenses")).isEqualTo(1);
        assertThat(orderJdbc.sql("""
                        SELECT amount FROM finance_cost_entries
                        WHERE cost_category = 'OPERATING_EXPENSE'
                        """).query(BigDecimal.class).single())
                .isEqualByComparingTo("80.0000");
        assertThat(orderJdbc.sql("""
                        SELECT business_date FROM finance_cost_entries
                        WHERE cost_category = 'OPERATING_EXPENSE'
                        """).query(LocalDate.class).single())
                .isEqualTo(LocalDate.parse("2026-10-05"));
        assertThat(orderJdbc.sql("""
                        SELECT cost_entry_business_id FROM finance_daily_cash_details
                        WHERE daily_record_business_id = :id
                        """).param("id", expense.dailyRecordBusinessId())
                .query(String.class).single())
                .isEqualTo(expense.costEntryBusinessId());

        // 幂等重试不重复现金或成本
        asAdmin(() -> cash.recordDailyCash(expenseCommand));
        assertThat(count("finance_operating_expenses")).isEqualTo(1);
    }

    @Test
    @DisplayName("经营费用写入失败时现金与日常记录同时回滚")
    void expenseFailureRollsBackCash() {
        OperatingExpenseService failing = new OperatingExpenseService() {
            @Override
            public OperatingExpenseRef record(OperatingExpenseCommand command) {
                throw new IllegalStateException("注入成本写入失败");
            }

            @Override
            public String reverse(String expenseBusinessId, String reasonText) {
                return expenseBusinessId;
            }
        };
        var service = new CashApplicationServiceImpl(
                cashRepository, orderPayments, afterSalesPayments, payrollPayments, failing,
                fileReferences, numbering, idempotency, authorization, currentActorProvider,
                audit, clock, transactionManager);

        assertThatThrownBy(() -> asAdmin(() -> service.recordDailyCash(
                new RecordDailyCashCommand(
                        uniqueKey("CASH-DAILY"), "OUT", "RENT",
                        LocalDate.parse("2026-10-06"), money("30.00"), "房租", true))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("注入");
        assertThat(count("finance_cash_facts")).isZero();
        assertThat(count("finance_daily_cash_details")).isZero();
        assertThat(count("finance_operating_expenses")).isZero();
    }

    private DailyCashRecordRef record(
            String direction, String category, String date, String amount,
            String description, boolean countAsExpense) {
        return asAdmin(() -> cash.recordDailyCash(new RecordDailyCashCommand(
                uniqueKey("CASH-DAILY"), direction, category, LocalDate.parse(date),
                money(amount), description, countAsExpense)));
    }

    private static SettlementMoney money(String amount) {
        return SettlementMoney.of(new BigDecimal(amount), CurrencyCode.CNY);
    }

    private long count(String table) {
        return orderJdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}

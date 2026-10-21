package com.yumi.finance.costledger.application;

import com.yumi.finance.costledger.api.OperatingExpenseCommand;
import com.yumi.finance.costledger.api.OperatingExpenseService;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 经营费用能力（基线 13 §8.4）：由 cash 在同一事务调用，成本与现金同提交/回滚。
 */
public class OperatingExpenseServiceImpl implements OperatingExpenseService {

    private final JdbcClient jdbc;

    public OperatingExpenseServiceImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public OperatingExpenseRef record(OperatingExpenseCommand command) {
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_EXPENSE", "经营费用命令不能为空");
        }
        insertExpense(command.expenseBusinessId(), command.cashFactBusinessId(),
                command.amount().value(), command.businessDate(), command.categoryCode(),
                "ACCRUE", null);
        String costEntryId = "CST-" + UUID.randomUUID();
        insertCostEntry(costEntryId, command.expenseBusinessId(), command.amount().value(),
                command.businessDate(), "ACCRUE");
        return new OperatingExpenseRef(command.expenseBusinessId(), costEntryId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String reverse(String expenseBusinessId, String reasonText) {
        var original = jdbc.sql("""
                        SELECT expense_business_id, cash_fact_business_id, amount, business_date,
                               category_code
                        FROM finance_operating_expenses
                        WHERE expense_business_id = :id AND operation_type = 'ACCRUE'
                        """)
                .param("id", expenseBusinessId)
                .query((resultSet, rowNumber) -> new Object[] {
                        resultSet.getString("cash_fact_business_id"),
                        resultSet.getBigDecimal("amount"),
                        resultSet.getObject("business_date", java.time.LocalDate.class),
                        resultSet.getString("category_code")})
                .optional()
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "OPERATING_EXPENSE_NOT_FOUND", "经营费用不存在"));
        String reversalId = "OPE-" + UUID.randomUUID();
        insertExpense(reversalId, (String) original[0], (java.math.BigDecimal) original[1],
                (java.time.LocalDate) original[2], (String) original[3], "REVERSE",
                expenseBusinessId);
        insertCostEntry("CST-" + UUID.randomUUID(), reversalId,
                ((java.math.BigDecimal) original[1]).negate(),
                (java.time.LocalDate) original[2], "REVERSE");
        return reversalId;
    }

    private void insertExpense(
            String id, String cashFactId, java.math.BigDecimal amount,
            java.time.LocalDate businessDate, String category, String operation,
            String reversesId) {
        jdbc.sql("""
                        INSERT INTO finance_operating_expenses (
                            expense_business_id, cash_fact_business_id, amount, business_date,
                            category_code, operation_type, reverses_expense_business_id, created_at)
                        VALUES (:id, :cashId, :amount, :businessDate, :category, :operation,
                                :reversesId, UTC_TIMESTAMP(6))
                        """)
                .param("id", id)
                .param("cashId", cashFactId)
                .param("amount", amount)
                .param("businessDate", java.sql.Date.valueOf(businessDate))
                .param("category", category)
                .param("operation", operation)
                .param("reversesId", reversesId)
                .update();
    }

    private void insertCostEntry(
            String costEntryId, String expenseId, java.math.BigDecimal amount,
            java.time.LocalDate businessDate, String operation) {
        jdbc.sql("""
                        INSERT INTO finance_cost_entries (
                            cost_entry_business_id, source_module, source_type, source_business_id,
                            source_version, cost_category, subject_type, subject_business_id, amount,
                            business_date, operation_type, occurred_at, created_at, allocated)
                        VALUES (:id, 'finance', 'OPERATING_EXPENSE', :expenseId, 1,
                                'OPERATING_EXPENSE', 'OPERATING_EXPENSE', :expenseId, :amount,
                                :businessDate, :operation, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0)
                        """)
                .param("id", costEntryId)
                .param("expenseId", expenseId)
                .param("amount", amount)
                .param("businessDate", java.sql.Date.valueOf(businessDate))
                .param("operation", operation)
                .update();
    }
}

package com.yumi.finance.cash.infrastructure;

import com.yumi.finance.cash.application.CashRepository;
import com.yumi.platform.api.jdbc.JdbcTimes;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 现金事实与来源投影持久化（MySQL）。 */
@Repository
public class JdbcCashRepository implements CashRepository {

    private final JdbcClient jdbc;

    public JdbcCashRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long lockSourceGuard(
            String sourceModule, String sourceType, String sourceBusinessId) {
        jdbc.sql("""
                        INSERT IGNORE INTO finance_source_guards (
                            source_module, source_type, source_business_id, row_version)
                        VALUES (:module, :type, :sourceId, 1)
                        """)
                .param("module", sourceModule)
                .param("type", sourceType)
                .param("sourceId", sourceBusinessId)
                .update();
        return jdbc.sql("""
                        SELECT row_version FROM finance_source_guards
                        WHERE source_module = :module AND source_type = :type
                          AND source_business_id = :sourceId FOR UPDATE
                        """)
                .param("module", sourceModule)
                .param("type", sourceType)
                .param("sourceId", sourceBusinessId)
                .query(Long.class).single();
    }

    @Override
    public void insertCashFact(CashFactRecord record) {
        jdbc.sql("""
                        INSERT INTO finance_cash_facts (
                            cash_fact_business_id, cash_no, direction, cash_type, business_date,
                            amount, currency_code, occurred_at, operator_id,
                            reverses_fact_business_id, is_valid)
                        VALUES (:id, :no, :direction, :type, :businessDate, :amount, :currency,
                                :occurredAt, :operatorId, :reversesId, 1)
                        """)
                .param("id", record.cashFactBusinessId())
                .param("no", record.cashNo())
                .param("direction", record.direction())
                .param("type", record.cashType())
                .param("businessDate", java.sql.Date.valueOf(record.businessDate()))
                .param("amount", record.amount())
                .param("currency", record.currencyCode())
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .param("operatorId", record.operatorId())
                .param("reversesId", record.reversesFactBusinessId())
                .update();
    }

    @Override
    public void insertAllocation(AllocationRecord record) {
        jdbc.sql("""
                        INSERT INTO finance_cash_allocations (
                            allocation_business_id, cash_fact_business_id, source_module,
                            source_type, source_business_id, amount)
                        VALUES (:id, :cashId, :module, :type, :sourceId, :amount)
                        """)
                .param("id", record.allocationBusinessId())
                .param("cashId", record.cashFactBusinessId())
                .param("module", record.sourceModule())
                .param("type", record.sourceType())
                .param("sourceId", record.sourceBusinessId())
                .param("amount", record.amount())
                .update();
    }

    @Override
    public Optional<CashFactRecord> findCashFact(String cashFactBusinessId) {
        return jdbc.sql("""
                        SELECT cash_fact_business_id, cash_no, direction, cash_type, business_date,
                               amount, currency_code, occurred_at, operator_id,
                               reverses_fact_business_id
                        FROM finance_cash_facts WHERE cash_fact_business_id = :id
                        """)
                .param("id", cashFactBusinessId)
                .query((resultSet, rowNumber) -> new CashFactRecord(
                        resultSet.getString("cash_fact_business_id"),
                        resultSet.getString("cash_no"),
                        resultSet.getString("direction"),
                        resultSet.getString("cash_type"),
                        resultSet.getObject("business_date", java.time.LocalDate.class),
                        resultSet.getBigDecimal("amount"),
                        resultSet.getString("currency_code"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "occurred_at", java.time.LocalDateTime.class)),
                        resultSet.getString("operator_id"),
                        resultSet.getString("reverses_fact_business_id")))
                .optional();
    }

    @Override
    public Optional<CashFactRecord> lockCashFact(String cashFactBusinessId) {
        return jdbc.sql("""
                        SELECT cash_fact_business_id, cash_no, direction, cash_type, business_date,
                               amount, currency_code, occurred_at, operator_id,
                               reverses_fact_business_id
                        FROM finance_cash_facts WHERE cash_fact_business_id = :id FOR UPDATE
                        """)
                .param("id", cashFactBusinessId)
                .query(JdbcCashRepository::mapCashFact)
                .optional();
    }

    @Override
    public Optional<CashFactRecord> findReversalOf(String cashFactBusinessId) {
        return jdbc.sql("""
                        SELECT cash_fact_business_id, cash_no, direction, cash_type, business_date,
                               amount, currency_code, occurred_at, operator_id,
                               reverses_fact_business_id
                        FROM finance_cash_facts WHERE reverses_fact_business_id = :id
                        """)
                .param("id", cashFactBusinessId)
                .query(JdbcCashRepository::mapCashFact)
                .optional();
    }

    @Override
    public void markCashFactInvalid(String cashFactBusinessId) {
        jdbc.sql("UPDATE finance_cash_facts SET is_valid = 0 WHERE cash_fact_business_id = :id")
                .param("id", cashFactBusinessId).update();
    }

    @Override
    public java.util.List<AllocationRecord> findAllocations(String cashFactBusinessId) {
        return jdbc.sql("""
                        SELECT allocation_business_id, cash_fact_business_id, source_module,
                               source_type, source_business_id, amount
                        FROM finance_cash_allocations WHERE cash_fact_business_id = :id
                        """)
                .param("id", cashFactBusinessId)
                .query((resultSet, rowNumber) -> new AllocationRecord(
                        resultSet.getString("allocation_business_id"),
                        resultSet.getString("cash_fact_business_id"),
                        resultSet.getString("source_module"),
                        resultSet.getString("source_type"),
                        resultSet.getString("source_business_id"),
                        resultSet.getBigDecimal("amount")))
                .list();
    }

    @Override
    public Optional<DailyDetailRecord> findDailyDetailByCashFact(String cashFactBusinessId) {
        return jdbc.sql("""
                        SELECT daily_record_business_id, cash_fact_business_id, category_code,
                               description, count_as_expense, cost_entry_business_id, created_at
                        FROM finance_daily_cash_details WHERE cash_fact_business_id = :id
                        """)
                .param("id", cashFactBusinessId)
                .query((resultSet, rowNumber) -> new DailyDetailRecord(
                        resultSet.getString("daily_record_business_id"),
                        resultSet.getString("cash_fact_business_id"),
                        resultSet.getString("category_code"),
                        resultSet.getString("description"),
                        resultSet.getBoolean("count_as_expense"),
                        resultSet.getString("cost_entry_business_id"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "created_at", java.time.LocalDateTime.class))))
                .optional();
    }

    @Override
    public Optional<PayrollReturnDetailRecord> findPayrollReturnDetail(String returnCashFactBusinessId) {
        return jdbc.sql("""
                        SELECT return_cash_fact_business_id, original_payment_cash_fact_business_id,
                               original_payment_allocation_business_id, result_business_id, amount,
                               reason_text, operation_type, corrects_return_detail_business_id
                        FROM finance_payroll_return_details
                        WHERE return_cash_fact_business_id = :id
                        """)
                .param("id", returnCashFactBusinessId)
                .query((resultSet, rowNumber) -> new PayrollReturnDetailRecord(
                        resultSet.getString("return_cash_fact_business_id"),
                        resultSet.getString("original_payment_cash_fact_business_id"),
                        resultSet.getString("original_payment_allocation_business_id"),
                        resultSet.getString("result_business_id"),
                        resultSet.getBigDecimal("amount"),
                        resultSet.getString("reason_text"),
                        resultSet.getString("operation_type"),
                        resultSet.getString("corrects_return_detail_business_id")))
                .optional();
    }

    @Override
    public ProjectionRecord projectionOf(
            String sourceModule, String sourceType, String sourceBusinessId) {
        return jdbc.sql("""
                        SELECT received_amount, paid_amount, refunded_amount
                        FROM finance_source_projections
                        WHERE source_module = :module AND source_type = :type
                          AND source_business_id = :sourceId
                        """)
                .param("module", sourceModule)
                .param("type", sourceType)
                .param("sourceId", sourceBusinessId)
                .query((resultSet, rowNumber) -> new ProjectionRecord(
                        sourceModule, sourceType, sourceBusinessId,
                        resultSet.getBigDecimal("received_amount"),
                        resultSet.getBigDecimal("paid_amount"),
                        resultSet.getBigDecimal("refunded_amount")))
                .optional()
                .orElse(new ProjectionRecord(
                        sourceModule, sourceType, sourceBusinessId,
                        java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                        java.math.BigDecimal.ZERO));
    }

    @Override
    public void upsertProjection(ProjectionRecord record) {
        jdbc.sql("""
                        INSERT INTO finance_source_projections (
                            source_module, source_type, source_business_id, received_amount,
                            paid_amount, refunded_amount, updated_at)
                        VALUES (:module, :type, :sourceId, :received, :paid, :refunded,
                                UTC_TIMESTAMP(6))
                        ON DUPLICATE KEY UPDATE received_amount = VALUES(received_amount),
                            paid_amount = VALUES(paid_amount), refunded_amount = VALUES(refunded_amount),
                            updated_at = UTC_TIMESTAMP(6)
                        """)
                .param("module", record.sourceModule())
                .param("type", record.sourceType())
                .param("sourceId", record.sourceBusinessId())
                .param("received", record.receivedAmount())
                .param("paid", record.paidAmount())
                .param("refunded", record.refundedAmount())
                .update();
    }

    @Override
    public void insertDailyDetail(DailyDetailRecord record) {
        jdbc.sql("""
                        INSERT INTO finance_daily_cash_details (
                            daily_record_business_id, cash_fact_business_id, category_code,
                            description, count_as_expense, cost_entry_business_id, created_at)
                        VALUES (:id, :cashId, :category, :description, :countAsExpense, NULL,
                                :createdAt)
                        """)
                .param("id", record.dailyRecordBusinessId())
                .param("cashId", record.cashFactBusinessId())
                .param("category", record.categoryCode())
                .param("description", record.description())
                .param("countAsExpense", record.countAsExpense())
                .param("createdAt", JdbcTimes.toUtc(record.createdAt()))
                .update();
    }

    @Override
    public void updateDailyCostEntry(String dailyRecordBusinessId, String costEntryBusinessId) {
        jdbc.sql("""
                        UPDATE finance_daily_cash_details SET cost_entry_business_id = :costId
                        WHERE daily_record_business_id = :id
                        """)
                .param("costId", costEntryBusinessId)
                .param("id", dailyRecordBusinessId)
                .update();
    }

    @Override
    public Optional<DailyDetailRecord> findDailyDetail(String dailyRecordBusinessId) {
        return jdbc.sql("""
                        SELECT daily_record_business_id, cash_fact_business_id, category_code,
                               description, count_as_expense, cost_entry_business_id, created_at
                        FROM finance_daily_cash_details WHERE daily_record_business_id = :id
                        """)
                .param("id", dailyRecordBusinessId)
                .query((resultSet, rowNumber) -> new DailyDetailRecord(
                        resultSet.getString("daily_record_business_id"),
                        resultSet.getString("cash_fact_business_id"),
                        resultSet.getString("category_code"),
                        resultSet.getString("description"),
                        resultSet.getBoolean("count_as_expense"),
                        resultSet.getString("cost_entry_business_id"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "created_at", java.time.LocalDateTime.class))))
                .optional();
    }

    @Override
    public void insertVoucherLink(VoucherLinkRecord record) {
        jdbc.sql("""
                        INSERT INTO finance_cash_voucher_links (
                            link_business_id, cash_fact_business_id, file_business_id,
                            purpose_code, created_at, operator_id)
                        VALUES (:id, :cashId, :fileId, :purpose, :createdAt, :operatorId)
                        """)
                .param("id", record.linkBusinessId())
                .param("cashId", record.cashFactBusinessId())
                .param("fileId", record.fileBusinessId())
                .param("purpose", record.purposeCode())
                .param("createdAt", JdbcTimes.toUtc(record.createdAt()))
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public java.util.List<VoucherLinkRecord> listVoucherLinks(String cashFactBusinessId) {
        return jdbc.sql("""
                        SELECT link_business_id, cash_fact_business_id, file_business_id,
                               purpose_code, created_at, operator_id
                        FROM finance_cash_voucher_links
                        WHERE cash_fact_business_id = :id ORDER BY created_at, link_business_id
                        """)
                .param("id", cashFactBusinessId)
                .query((resultSet, rowNumber) -> new VoucherLinkRecord(
                        resultSet.getString("link_business_id"),
                        resultSet.getString("cash_fact_business_id"),
                        resultSet.getString("file_business_id"),
                        resultSet.getString("purpose_code"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "created_at", java.time.LocalDateTime.class)),
                        resultSet.getString("operator_id")))
                .list();
    }

    @Override
    public Optional<AllocationRecord> findAllocation(
            String cashFactBusinessId, String sourceType, String sourceBusinessId) {
        return jdbc.sql("""
                        SELECT allocation_business_id, cash_fact_business_id, source_module,
                               source_type, source_business_id, amount
                        FROM finance_cash_allocations
                        WHERE cash_fact_business_id = :cashId AND source_type = :type
                          AND source_business_id = :sourceId
                        """)
                .param("cashId", cashFactBusinessId)
                .param("type", sourceType)
                .param("sourceId", sourceBusinessId)
                .query((resultSet, rowNumber) -> new AllocationRecord(
                        resultSet.getString("allocation_business_id"),
                        resultSet.getString("cash_fact_business_id"),
                        resultSet.getString("source_module"),
                        resultSet.getString("source_type"),
                        resultSet.getString("source_business_id"),
                        resultSet.getBigDecimal("amount")))
                .optional();
    }

    @Override
    public java.math.BigDecimal returnedAmount(
            String originalCashFactBusinessId, String resultBusinessId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(CASE WHEN operation_type = 'ACCRUE' THEN amount
                                                 ELSE -amount END), 0)
                        FROM finance_payroll_return_details
                        WHERE original_payment_cash_fact_business_id = :cashId
                          AND result_business_id = :resultId AND is_valid = 1
                        """)
                .param("cashId", originalCashFactBusinessId)
                .param("resultId", resultBusinessId)
                .query(java.math.BigDecimal.class).single();
    }

    @Override
    public void insertPayrollReturnDetail(PayrollReturnDetailRecord record) {
        jdbc.sql("""
                        INSERT INTO finance_payroll_return_details (
                            return_cash_fact_business_id, original_payment_cash_fact_business_id,
                            original_payment_allocation_business_id, result_business_id, amount,
                            reason_text, operation_type, corrects_return_detail_business_id, is_valid)
                        VALUES (:returnCashId, :originalCashId, :allocationId, :resultId,
                                :amount, :reason, :operation, :correctsId, 1)
                        """)
                .param("returnCashId", record.returnCashFactBusinessId())
                .param("originalCashId", record.originalPaymentCashFactBusinessId())
                .param("allocationId", record.originalPaymentAllocationBusinessId())
                .param("resultId", record.resultBusinessId())
                .param("amount", record.amount())
                .param("reason", record.reasonText())
                .param("operation", record.operationType())
                .param("correctsId", record.correctsReturnDetailBusinessId())
                .update();
    }

    private static CashFactRecord mapCashFact(
            java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new CashFactRecord(
                resultSet.getString("cash_fact_business_id"),
                resultSet.getString("cash_no"),
                resultSet.getString("direction"),
                resultSet.getString("cash_type"),
                resultSet.getObject("business_date", java.time.LocalDate.class),
                resultSet.getBigDecimal("amount"),
                resultSet.getString("currency_code"),
                JdbcTimes.toInstant(resultSet.getObject(
                        "occurred_at", java.time.LocalDateTime.class)),
                resultSet.getString("operator_id"),
                resultSet.getString("reverses_fact_business_id"));
    }
}

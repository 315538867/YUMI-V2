package com.yumi.finance.costledger.infrastructure;

import com.yumi.finance.costledger.api.CostLedgerQuery;
import com.yumi.platform.api.jdbc.JdbcTimes;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 成本明细、失败队列与主体汇总查询实现。 */
public class CostLedgerQueryImpl implements CostLedgerQuery {

    private final JdbcClient jdbc;

    public CostLedgerQueryImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CostEntryView> listEntries(
            String subjectType, String subjectId, java.time.LocalDate startDate,
            java.time.LocalDate endDate) {
        return jdbc.sql("""
                        SELECT cost_entry_business_id, cost_category, subject_type,
                               subject_business_id, amount, business_date, operation_type,
                               source_module, source_type, source_business_id,
                               basis_ref, reason_text, allocated, occurred_at
                        FROM finance_cost_entries
                        WHERE subject_type = :subjectType AND subject_business_id = :subjectId
                          AND business_date BETWEEN :startDate AND :endDate
                        ORDER BY business_date, feed_sequence
                        """)
                .param("subjectType", subjectType)
                .param("subjectId", subjectId)
                .param("startDate", java.sql.Date.valueOf(startDate))
                .param("endDate", java.sql.Date.valueOf(endDate))
                .query((resultSet, rowNumber) -> new CostEntryView(
                        resultSet.getString("cost_entry_business_id"),
                        resultSet.getString("cost_category"),
                        resultSet.getString("subject_type"),
                        resultSet.getString("subject_business_id"),
                        resultSet.getBigDecimal("amount"),
                        resultSet.getObject("business_date", java.time.LocalDate.class),
                        resultSet.getString("operation_type"),
                        resultSet.getString("source_module"),
                        resultSet.getString("source_type"),
                        resultSet.getString("source_business_id"),
                        resultSet.getString("basis_ref"),
                        resultSet.getString("reason_text"),
                        !"REVERSE".equals(resultSet.getString("operation_type")),
                        resultSet.getBoolean("allocated"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "occurred_at", java.time.LocalDateTime.class))))
                .list();
    }

    @Override
    public List<CostFailureView> listPendingFailures() {
        return jdbc.sql("""
                        SELECT failure_business_id, source_module, source_type, source_business_id,
                               reason_text, attempts, last_error, created_at
                        FROM finance_cost_failures
                        WHERE status = 'PENDING'
                        ORDER BY created_at
                        """)
                .query((resultSet, rowNumber) -> new CostFailureView(
                        resultSet.getString("failure_business_id"),
                        resultSet.getString("source_module"),
                        resultSet.getString("source_type"),
                        resultSet.getString("source_business_id"),
                        resultSet.getString("reason_text"),
                        resultSet.getInt("attempts"),
                        resultSet.getString("last_error"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "created_at", java.time.LocalDateTime.class))))
                .list();
    }

    @Override
    public CostSubjectSummary getSubjectCost(
            String subjectType, String subjectId, java.time.LocalDate startDate,
            java.time.LocalDate endDate) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(amount), 0) AS total,
                               COALESCE(SUM(CASE WHEN operation_type <> 'REVERSE' AND allocated = 0
                                                 THEN 1 ELSE 0 END), 0) AS unallocated
                        FROM finance_cost_entries
                        WHERE subject_type = :subjectType AND subject_business_id = :subjectId
                          AND business_date BETWEEN :startDate AND :endDate
                        """)
                .param("subjectType", subjectType)
                .param("subjectId", subjectId)
                .param("startDate", java.sql.Date.valueOf(startDate))
                .param("endDate", java.sql.Date.valueOf(endDate))
                .query((resultSet, rowNumber) -> new CostSubjectSummary(
                        subjectType, subjectId, resultSet.getBigDecimal("total"),
                        resultSet.getLong("unallocated") == 0))
                .single();
    }
}

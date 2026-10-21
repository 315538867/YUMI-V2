package com.yumi.finance.cash.infrastructure;

import com.yumi.finance.cash.api.CashQuery;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 现金查询实现。 */
public class CashQueryImpl implements CashQuery {

    private final JdbcClient jdbc;

    public CashQueryImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CashPeriodSummary getPeriodSummary(java.time.LocalDate startDate,
            java.time.LocalDate endDate) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(CASE WHEN direction = 'IN' THEN amount ELSE 0 END), 0)
                                   AS income,
                               COALESCE(SUM(CASE WHEN direction = 'OUT' THEN amount ELSE 0 END), 0)
                                   AS outflow
                        FROM finance_cash_facts
                        WHERE business_date BETWEEN :startDate AND :endDate AND is_valid = 1
                        """)
                .param("startDate", java.sql.Date.valueOf(startDate))
                .param("endDate", java.sql.Date.valueOf(endDate))
                .query((resultSet, rowNumber) -> {
                    var income = resultSet.getBigDecimal("income");
                    var outflow = resultSet.getBigDecimal("outflow");
                    return new CashPeriodSummary(
                            startDate, endDate, income, outflow, income.subtract(outflow));
                })
                .single();
    }

    @Override
    public List<CashFactView> search(
            java.time.LocalDate startDate, java.time.LocalDate endDate, String cashType) {
        return jdbc.sql("""
                        SELECT cash_fact_business_id, cash_no, direction, cash_type, business_date,
                               amount, is_valid
                        FROM finance_cash_facts
                        WHERE business_date BETWEEN :startDate AND :endDate
                          AND (:cashType = '' OR cash_type = :cashType)
                        ORDER BY business_date, feed_sequence
                        """)
                .param("startDate", java.sql.Date.valueOf(startDate))
                .param("endDate", java.sql.Date.valueOf(endDate))
                .param("cashType", cashType == null ? "" : cashType)
                .query((resultSet, rowNumber) -> new CashFactView(
                        resultSet.getString("cash_fact_business_id"),
                        resultSet.getString("cash_no"),
                        resultSet.getString("direction"),
                        resultSet.getString("cash_type"),
                        resultSet.getObject("business_date", java.time.LocalDate.class),
                        resultSet.getBigDecimal("amount"),
                        resultSet.getBoolean("is_valid")))
                .list();
    }

    @Override
    public SourceCashSummary getSourceSummary(
            String sourceModule, String sourceType, String sourceBusinessId) {
        return jdbc.sql("""
                        SELECT COALESCE(received_amount, 0), COALESCE(paid_amount, 0),
                               COALESCE(refunded_amount, 0)
                        FROM finance_source_projections
                        WHERE source_module = :module AND source_type = :type
                          AND source_business_id = :sourceId
                        """)
                .param("module", sourceModule)
                .param("type", sourceType)
                .param("sourceId", sourceBusinessId)
                .query((resultSet, rowNumber) -> new SourceCashSummary(
                        sourceModule, sourceType, sourceBusinessId,
                        resultSet.getBigDecimal(1), resultSet.getBigDecimal(2),
                        resultSet.getBigDecimal(3)))
                .optional()
                .orElse(new SourceCashSummary(
                        sourceModule, sourceType, sourceBusinessId,
                        java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                        java.math.BigDecimal.ZERO));
    }
}

package com.yumi.finance.cash.infrastructure;

import com.yumi.finance.cash.api.CashReportingFeed;
import com.yumi.platform.api.jdbc.JdbcTimes;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 现金变化 Feed 实现。 */
public class CashReportingFeedImpl implements CashReportingFeed {

    private final JdbcClient jdbc;

    public CashReportingFeedImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CashChangePage changedSince(CashChangeCursor cursor, int limit) {
        if (limit <= 0 || limit > 500) {
            throw new IllegalArgumentException("现金变化分页大小必须在 1 到 500 之间");
        }
        long after = cursor == null ? 0 : cursor.afterSequence();
        List<CashChangeItem> items = jdbc.sql("""
                        SELECT feed_sequence, cash_fact_business_id, cash_no, direction, cash_type,
                               business_date, amount, is_valid, occurred_at
                        FROM finance_cash_facts
                        WHERE feed_sequence > :after
                        ORDER BY feed_sequence
                        LIMIT :limit
                        """)
                .param("after", after)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new CashChangeItem(
                        resultSet.getLong("feed_sequence"),
                        resultSet.getString("cash_fact_business_id"),
                        resultSet.getString("cash_no"),
                        resultSet.getString("direction"),
                        resultSet.getString("cash_type"),
                        resultSet.getObject("business_date", java.time.LocalDate.class),
                        resultSet.getBigDecimal("amount"),
                        resultSet.getBoolean("is_valid"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "occurred_at", java.time.LocalDateTime.class))))
                .list();
        CashChangeCursor next = items.size() < limit
                ? null : new CashChangeCursor(items.getLast().feedSequence());
        return new CashChangePage(items, next);
    }
}

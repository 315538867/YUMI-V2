package com.yumi.finance.costledger.infrastructure;

import com.yumi.finance.costledger.api.CostReportingFeed;
import com.yumi.platform.api.jdbc.JdbcTimes;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 成本变化 Feed 实现。 */
public class CostReportingFeedImpl implements CostReportingFeed {

    private final JdbcClient jdbc;

    public CostReportingFeedImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CostChangePage changedSince(CostChangeCursor cursor, int limit) {
        if (limit <= 0 || limit > 500) {
            throw new IllegalArgumentException("成本变化分页大小必须在 1 到 500 之间");
        }
        long after = cursor == null ? 0 : cursor.afterSequence();
        List<CostChangeItem> items = jdbc.sql("""
                        SELECT feed_sequence, cost_entry_business_id, cost_category, subject_type,
                               subject_business_id, amount, business_date, operation_type, occurred_at
                        FROM finance_cost_entries
                        WHERE feed_sequence > :after
                        ORDER BY feed_sequence
                        LIMIT :limit
                        """)
                .param("after", after)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new CostChangeItem(
                        resultSet.getLong("feed_sequence"),
                        resultSet.getString("cost_entry_business_id"),
                        resultSet.getString("cost_category"),
                        resultSet.getString("subject_type"),
                        resultSet.getString("subject_business_id"),
                        resultSet.getBigDecimal("amount"),
                        resultSet.getObject("business_date", java.time.LocalDate.class),
                        resultSet.getString("operation_type"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "occurred_at", java.time.LocalDateTime.class))))
                .list();
        CostChangeCursor next = items.size() < limit
                ? null : new CostChangeCursor(items.getLast().feedSequence());
        return new CostChangePage(items, next);
    }
}

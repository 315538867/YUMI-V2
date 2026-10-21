package com.yumi.customer.infrastructure;

import com.yumi.customer.api.CustomerReportingFeed;
import com.yumi.customer.api.CustomerStatus;
import com.yumi.platform.api.jdbc.JdbcTimes;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 客户变化数据源实现。 */
public class CustomerReportingFeedImpl implements CustomerReportingFeed {

    private final JdbcClient jdbc;

    public CustomerReportingFeedImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CustomerChangePage changedSince(String cursor, int limit) {
        if (limit <= 0 || limit > 500) {
            throw new IllegalArgumentException("客户变化分页大小必须在 1 到 500 之间");
        }
        java.time.Instant after = java.time.Instant.EPOCH;
        String afterId = "";
        if (cursor != null && !cursor.isBlank()) {
            String[] parts = cursor.split("\\|", 2);
            after = java.time.Instant.ofEpochMilli(Long.parseLong(parts[0]));
            afterId = parts.length > 1 ? parts[1] : "";
        }
        List<CustomerChangeItem> items = jdbc.sql("""
                        SELECT customer_business_id, customer_no, status, content_version, updated_at
                        FROM customer_customers
                        WHERE updated_at > :after
                           OR (updated_at = :after AND customer_business_id > :afterId)
                        ORDER BY updated_at, customer_business_id
                        LIMIT :limit
                        """)
                .param("after", JdbcTimes.toUtc(after))
                .param("afterId", afterId)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new CustomerChangeItem(
                        resultSet.getString("customer_business_id"),
                        resultSet.getString("customer_no"),
                        CustomerStatus.valueOf(resultSet.getString("status")),
                        resultSet.getLong("content_version"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "updated_at", java.time.LocalDateTime.class)),
                        "CHANGE"))
                .list();
        String next = items.size() < limit ? null
                : items.getLast().occurredAt().toEpochMilli() + "|"
                        + items.getLast().customerBusinessId();
        return new CustomerChangePage(items, next);
    }
}

package com.yumi.product.infrastructure;

import com.yumi.platform.api.jdbc.JdbcTimes;
import com.yumi.product.api.ProductReportingFeed;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 商品变化数据源实现。 */
public class ProductReportingFeedImpl implements ProductReportingFeed {

    private final JdbcClient jdbc;

    public ProductReportingFeedImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ProductChangePage changedSince(ProductChangeCursor cursor, int limit) {
        if (limit <= 0 || limit > 500) {
            throw new IllegalArgumentException("商品变化分页大小必须在 1 到 500 之间");
        }
        java.time.Instant after = cursor == null ? java.time.Instant.EPOCH : cursor.updatedAt();
        String afterId = cursor == null ? "" : cursor.productBusinessId();
        List<ProductChangeItem> items = jdbc.sql("""
                        SELECT product_business_id, product_no, name, status, content_version, updated_at
                        FROM product_products
                        WHERE updated_at > :after
                           OR (updated_at = :after AND product_business_id > :afterId)
                        ORDER BY updated_at, product_business_id
                        LIMIT :limit
                        """)
                .param("after", JdbcTimes.toUtc(after))
                .param("afterId", afterId)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new ProductChangeItem(
                        resultSet.getString("product_business_id"),
                        resultSet.getString("product_no"),
                        resultSet.getString("name"),
                        resultSet.getString("status"),
                        resultSet.getLong("content_version"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "updated_at", java.time.LocalDateTime.class))))
                .list();
        ProductChangeCursor next = items.size() < limit ? null
                : new ProductChangeCursor(
                        items.getLast().updatedAt(), items.getLast().productBusinessId());
        return new ProductChangePage(items, next);
    }
}

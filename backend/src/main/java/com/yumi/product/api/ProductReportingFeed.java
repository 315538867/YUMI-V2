package com.yumi.product.api;

import java.time.Instant;
import java.util.List;

/** 商品变化数据源：按 {@code (updatedAt, productId)} 键集分页。 */
public interface ProductReportingFeed {

    ProductChangePage changedSince(ProductChangeCursor cursor, int limit);

    record ProductChangeCursor(Instant updatedAt, String productBusinessId) {
        public ProductChangeCursor {
            if (updatedAt == null || productBusinessId == null || productBusinessId.isBlank()) {
                throw new IllegalArgumentException("商品变化游标必须包含更新时间与商品标识");
            }
        }
    }

    record ProductChangePage(List<ProductChangeItem> items, ProductChangeCursor nextCursor) {
        public ProductChangePage {
            items = List.copyOf(items);
        }
    }

    record ProductChangeItem(
            String productBusinessId, String productNo, String name, String status,
            long contentVersion, Instant updatedAt) {
    }
}

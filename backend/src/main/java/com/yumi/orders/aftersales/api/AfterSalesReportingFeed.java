package com.yumi.orders.aftersales.api;

import java.time.Instant;
import java.util.List;

/**
 * 售后案例变化 Feed：按 {@code (updatedAt, caseBusinessId)} 键集分页。
 */
public interface AfterSalesReportingFeed {

    AfterSalesChangePage changedSince(AfterSalesChangeCursor cursor, int limit);

    record AfterSalesChangeCursor(Instant updatedAt, String caseBusinessId) {
        public AfterSalesChangeCursor {
            if (updatedAt == null || caseBusinessId == null || caseBusinessId.isBlank()) {
                throw new IllegalArgumentException("售后变化游标必须包含更新时间与案例标识");
            }
        }
    }

    record AfterSalesChangePage(List<AfterSalesChangeItem> items, AfterSalesChangeCursor nextCursor) {
        public AfterSalesChangePage {
            items = List.copyOf(items);
        }
    }

    record AfterSalesChangeItem(
            String caseBusinessId,
            String caseNo,
            String orderBusinessId,
            String customerBusinessId,
            String routeType,
            String status,
            long contentVersion,
            Instant updatedAt) {
    }
}

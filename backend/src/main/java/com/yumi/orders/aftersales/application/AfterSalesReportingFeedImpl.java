package com.yumi.orders.aftersales.application;

import com.yumi.orders.aftersales.api.AfterSalesReportingFeed;
import com.yumi.orders.aftersales.application.port.AfterSalesRepository;
import java.time.Instant;
import java.util.List;

/** 售后案例变化 Feed 实现。 */
public class AfterSalesReportingFeedImpl implements AfterSalesReportingFeed {

    private final AfterSalesRepository cases;

    public AfterSalesReportingFeedImpl(AfterSalesRepository cases) {
        this.cases = cases;
    }

    @Override
    public AfterSalesChangePage changedSince(AfterSalesChangeCursor cursor, int limit) {
        if (limit <= 0 || limit > 500) {
            throw new IllegalArgumentException("售后变化分页大小必须在 1 到 500 之间");
        }
        Instant after = cursor == null ? Instant.EPOCH : cursor.updatedAt();
        String afterId = cursor == null ? "" : cursor.caseBusinessId();
        var records = cases.findCasesChangedAfter(after, afterId, limit);
        List<AfterSalesChangeItem> items = records.stream()
                .map(record -> new AfterSalesChangeItem(
                        record.caseBusinessId(), record.caseNo(), record.orderBusinessId(),
                        record.customerBusinessId(), record.routeType(), record.status(),
                        record.contentVersion(), record.updatedAt()))
                .toList();
        AfterSalesChangeCursor next = records.size() < limit
                ? null : new AfterSalesChangeCursor(
                        records.getLast().updatedAt(), records.getLast().caseBusinessId());
        return new AfterSalesChangePage(items, next);
    }
}

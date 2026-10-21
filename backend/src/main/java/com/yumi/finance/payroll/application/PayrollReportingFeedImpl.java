package com.yumi.finance.payroll.application;

import com.yumi.finance.payroll.api.PayrollReportingFeed;
import java.time.Instant;
import java.util.List;

/** 工资变化 Feed 实现。 */
public class PayrollReportingFeedImpl implements PayrollReportingFeed {

    private final PayrollRepository payroll;

    public PayrollReportingFeedImpl(PayrollRepository payroll) {
        this.payroll = payroll;
    }

    @Override
    public PayrollChangePage changedSince(PayrollChangeCursor cursor, int limit) {
        if (limit <= 0 || limit > 500) {
            throw new IllegalArgumentException("工资变化分页大小必须在 1 到 500 之间");
        }
        Instant after = cursor == null ? Instant.EPOCH : cursor.updatedAt();
        String afterId = cursor == null ? "" : cursor.resultBusinessId();
        var rows = payroll.findResultsChangedAfter(after, afterId, limit);
        List<PayrollChangeItem> items = rows.stream()
                .map(row -> new PayrollChangeItem(
                        row.resultBusinessId(), row.employeeBusinessId(), row.periodBusinessId(),
                        row.periodStartDate(), row.status(), row.confirmedAmount(),
                        row.payableAmount(), row.paidAmount(), row.rowVersion(),
                        row.updatedAt()))
                .toList();
        PayrollChangeCursor next = rows.size() < limit
                ? null : new PayrollChangeCursor(
                        rows.getLast().updatedAt(), rows.getLast().resultBusinessId());
        return new PayrollChangePage(items, next);
    }
}

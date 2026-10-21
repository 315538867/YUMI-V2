package com.yumi.finance.payroll.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 工资变化 Feed：按 {@code (updatedAt, resultBusinessId)} 键集分页。 */
public interface PayrollReportingFeed {

    PayrollChangePage changedSince(PayrollChangeCursor cursor, int limit);

    record PayrollChangeCursor(Instant updatedAt, String resultBusinessId) {
        public PayrollChangeCursor {
            if (updatedAt == null || resultBusinessId == null || resultBusinessId.isBlank()) {
                throw new IllegalArgumentException("工资变化游标必须包含更新时间与结果标识");
            }
        }
    }

    record PayrollChangePage(List<PayrollChangeItem> items, PayrollChangeCursor nextCursor) {
        public PayrollChangePage {
            items = List.copyOf(items);
        }
    }

    record PayrollChangeItem(
            String resultBusinessId, String employeeBusinessId, String periodBusinessId,
            LocalDate periodStartDate, String status, BigDecimal confirmedAmount,
            BigDecimal payableAmount, BigDecimal paidAmount, long contentVersion,
            Instant updatedAt) {
    }
}

package com.yumi.finance.costledger.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 成本变化 Feed：游标由本子域解释，按内部序号稳定分页。 */
public interface CostReportingFeed {

    CostChangePage changedSince(CostChangeCursor cursor, int limit);

    record CostChangeCursor(long afterSequence) {
    }

    record CostChangePage(List<CostChangeItem> items, CostChangeCursor nextCursor) {
        public CostChangePage {
            items = List.copyOf(items);
        }
    }

    record CostChangeItem(
            long feedSequence, String costEntryBusinessId, String costCategory,
            String subjectType, String subjectBusinessId, BigDecimal amount,
            java.time.LocalDate businessDate, String operationType, Instant occurredAt) {
    }
}

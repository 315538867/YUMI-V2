package com.yumi.finance.cash.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 现金变化 Feed：按本子域序号稳定分页。 */
public interface CashReportingFeed {

    CashChangePage changedSince(CashChangeCursor cursor, int limit);

    record CashChangeCursor(long afterSequence) {
    }

    record CashChangePage(List<CashChangeItem> items, CashChangeCursor nextCursor) {
        public CashChangePage {
            items = List.copyOf(items);
        }
    }

    record CashChangeItem(
            long feedSequence, String cashFactBusinessId, String cashNo, String direction,
            String cashType, LocalDate businessDate, BigDecimal amount, boolean valid,
            Instant occurredAt) {
    }
}

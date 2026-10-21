package com.yumi.orders.aftersales.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 售后成本来源 Feed：按本模块自增序号稳定分页，供 finance.costledger 下游归集。
 */
public interface OrderAfterSalesCostSourceFeed {

    AfterSalesCostSourcePage listAfter(AfterSalesCostCursor cursor, int limit);

    record AfterSalesCostCursor(long afterSequence) {
    }

    record AfterSalesCostSourcePage(
            List<AfterSalesCostSourceItem> items, AfterSalesCostCursor nextCursor) {
        public AfterSalesCostSourcePage {
            items = List.copyOf(items);
        }
    }

    record AfterSalesCostSourceItem(
            long feedSequence,
            String sourceBusinessId,
            int sourceVersion,
            String caseBusinessId,
            String orderBusinessId,
            String costCategory,
            BigDecimal amount,
            LocalDate businessDate,
            String operationType,
            String basisRef,
            String reasonText,
            Instant occurredAt) {
    }
}

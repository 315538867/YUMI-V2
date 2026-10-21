package com.yumi.finance.costledger.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 成本明细、失败队列与主体汇总查询。 */
public interface CostLedgerQuery {

    List<CostEntryView> listEntries(
            String subjectType, String subjectId, LocalDate startDate, LocalDate endDate);

    List<CostFailureView> listPendingFailures();

    CostSubjectSummary getSubjectCost(
            String subjectType, String subjectId, LocalDate startDate, LocalDate endDate);

    record CostEntryView(
            String costEntryBusinessId, String costCategory, String subjectType,
            String subjectBusinessId, BigDecimal amount, LocalDate businessDate,
            String operationType, boolean allocated, Instant occurredAt) {
    }

    record CostFailureView(
            String failureBusinessId, String sourceModule, String sourceType,
            String sourceBusinessId, String reasonText, int attempts, String lastError,
            Instant createdAt) {
    }

    record CostSubjectSummary(
            String subjectType, String subjectId, BigDecimal totalAmount,
            boolean fullyAllocated) {
    }
}

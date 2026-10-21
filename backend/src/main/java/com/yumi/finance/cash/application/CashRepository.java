package com.yumi.finance.cash.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 现金事实、来源收付投影与凭证持久化端口。 */
public interface CashRepository {

    long lockSourceGuard(String sourceModule, String sourceType, String sourceBusinessId);

    void insertCashFact(CashFactRecord record);

    void insertAllocation(AllocationRecord record);

    Optional<CashFactRecord> findCashFact(String cashFactBusinessId);

    Optional<CashFactRecord> lockCashFact(String cashFactBusinessId);

    Optional<CashFactRecord> findReversalOf(String cashFactBusinessId);

    void markCashFactInvalid(String cashFactBusinessId);

    List<AllocationRecord> findAllocations(String cashFactBusinessId);

    Optional<DailyDetailRecord> findDailyDetailByCashFact(String cashFactBusinessId);

    Optional<PayrollReturnDetailRecord> findPayrollReturnDetail(String returnCashFactBusinessId);

    ProjectionRecord projectionOf(String sourceModule, String sourceType, String sourceBusinessId);

    void upsertProjection(ProjectionRecord record);

    void insertDailyDetail(DailyDetailRecord record);

    void updateDailyCostEntry(String dailyRecordBusinessId, String costEntryBusinessId);

    Optional<DailyDetailRecord> findDailyDetail(String dailyRecordBusinessId);

    void insertVoucherLink(VoucherLinkRecord record);

    List<VoucherLinkRecord> listVoucherLinks(String cashFactBusinessId);

    Optional<AllocationRecord> findAllocation(
            String cashFactBusinessId, String sourceType, String sourceBusinessId);

    java.math.BigDecimal returnedAmount(String originalCashFactBusinessId, String resultBusinessId);

    void insertPayrollReturnDetail(PayrollReturnDetailRecord record);

    record PayrollReturnDetailRecord(
            String returnCashFactBusinessId, String originalPaymentCashFactBusinessId,
            String originalPaymentAllocationBusinessId, String resultBusinessId,
            java.math.BigDecimal amount, String reasonText, String operationType,
            String correctsReturnDetailBusinessId) {
    }

    record VoucherLinkRecord(
            String linkBusinessId, String cashFactBusinessId, String fileBusinessId,
            String purposeCode, Instant createdAt, String operatorId) {
    }

    record DailyDetailRecord(
            String dailyRecordBusinessId, String cashFactBusinessId, String categoryCode,
            String description, boolean countAsExpense, String costEntryBusinessId,
            Instant createdAt) {
    }

    record CashFactRecord(
            String cashFactBusinessId, String cashNo, String direction, String cashType,
            LocalDate businessDate, BigDecimal amount, String currencyCode, Instant occurredAt,
            String operatorId, String reversesFactBusinessId) {
    }

    record AllocationRecord(
            String allocationBusinessId, String cashFactBusinessId, String sourceModule,
            String sourceType, String sourceBusinessId, BigDecimal amount) {
    }

    record ProjectionRecord(
            String sourceModule, String sourceType, String sourceBusinessId,
            BigDecimal receivedAmount, BigDecimal paidAmount, BigDecimal refundedAmount) {
    }
}

package com.yumi.finance.payroll.application;

import com.yumi.platform.api.value.SettlementMoney;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 工资期间、结果与参考运行持久化端口。 */
public interface PayrollRepository {

    boolean periodsOverlap(LocalDate startDate, LocalDate endDate);

    Optional<PeriodRecord> findPeriodByNo(String periodNo);

    Optional<PeriodRecord> findPeriod(String periodBusinessId);

    void insertPeriod(PeriodRecord record);

    List<PeriodRecord> listPeriods();

    Optional<ResultRecord> findResult(String periodBusinessId, String employeeBusinessId);

    Optional<ResultRecord> findResultById(String resultBusinessId);

    Optional<ResultRecord> lockResultById(String resultBusinessId);

    ResultRecord ensureResult(String periodBusinessId, String employeeBusinessId);

    void updateResultReference(
            String resultBusinessId, String runBusinessId, String fingerprint, String status);

    List<ResultRecord> listResults(String periodBusinessId);

    void insertReferenceRun(ReferenceRunRecord record);

    void insertReferenceLine(ReferenceLineRecord record);

    List<ReferenceLineRecord> listReferenceLines(String runBusinessId);

    Long monthGuardVersion(String employeeBusinessId, LocalDate monthStart);

    List<String> distinctLaborEmployees(LocalDate startDate, LocalDate endDate);

    void confirmResult(String resultBusinessId, BigDecimal confirmedAmount, long expectedRowVersion);

    void insertConfirmation(ConfirmationRecord record);

    void insertProcessingItem(ProcessingItemRecord record);

    void insertDirectLaborCost(DirectLaborCostRecord record);

    Optional<ConfirmationRecord> findConfirmationByResult(String resultBusinessId);

    List<ProcessingItemRecord> listProcessingItems(String resultBusinessId);

    void insertPaymentReturn(PaymentReturnRecord record);

    Optional<PaymentLinkRecord> findPaymentLinkByCashFact(String financeCashFactBusinessId);

    Optional<PaymentReturnRecord> findPaymentReturnByCashFact(String returnCashFactId);

    Optional<PaymentReturnRecord> findReturnReversal(String originalReturnLinkId);

    record PaymentReturnRecord(
            String returnLinkBusinessId, String resultBusinessId,
            String originalFinanceCashFactId, String returnFinanceCashFactId, BigDecimal amount,
            String operationType, String correctsReturnLinkBusinessId, boolean valid) {
    }



    record ConfirmationRecord(
            String confirmationBusinessId, String resultBusinessId, BigDecimal confirmedAmount,
            String referenceRunBusinessId, String referenceFingerprint, String note,
            Instant occurredAt, String operatorId) {
    }

    record ProcessingItemRecord(
            String itemBusinessId, String resultBusinessId, String confirmationBusinessId,
            String referenceLineBusinessId, BigDecimal manualAmount, String note,
            Instant createdAt) {
    }

    record DirectLaborCostRecord(
            String costSourceBusinessId, String resultBusinessId, BigDecimal amount,
            LocalDate businessDate, Instant createdAt) {
    }

    record PeriodRecord(
            String periodBusinessId, String periodNo, LocalDate startDate, LocalDate endDate,
            String status, long contentVersion, long rowVersion, Instant createdAt) {
    }

    record ResultRecord(
            String resultBusinessId, String periodBusinessId, String employeeBusinessId,
            String status, String referenceRunBusinessId, String referenceFingerprint,
            BigDecimal confirmedAmount, BigDecimal payableAmount, long rowVersion) {
    }

    Optional<ReferenceRunRecord> findRun(String runBusinessId);

    void insertAdjustment(AdjustmentRecord record);

    void updatePayableAmount(
            String resultBusinessId, BigDecimal payableAmount, long expectedRowVersion);

    void insertCostAdjustment(CostAdjustmentRecord record);

    BigDecimal paidAmount(String resultBusinessId);

    void insertPaymentLink(PaymentLinkRecord record);

    Optional<PaymentLinkRecord> findPaymentLink(String linkBusinessId);

    Optional<PaymentLinkRecord> findReversalOf(String originalLinkId);

    void upsertPaidAmount(String resultBusinessId, BigDecimal paidAmount);

    List<DirectLaborCostRow> listDirectLaborCosts(java.time.LocalDate start, java.time.LocalDate end);

    List<CostAdjustmentRow> listCostAdjustments(java.time.LocalDate start, java.time.LocalDate end);

    record DirectLaborCostRow(
            String costSourceBusinessId, String resultBusinessId, String employeeBusinessId,
            BigDecimal amount, java.time.LocalDate businessDate, Instant createdAt) {
    }

    record CostAdjustmentRow(
            String adjustmentBusinessId, String resultBusinessId, String employeeBusinessId,
            BigDecimal amountDelta, java.time.LocalDate businessDate, Instant createdAt) {
    }

    List<PayrollFeedRow> findResultsChangedAfter(
            Instant updatedAfter, String afterResultId, int limit);

    record PaymentLinkRecord(
            String linkBusinessId, String resultBusinessId, String financeCashFactBusinessId,
            BigDecimal amount, String reversesLinkBusinessId, String reasonText,
            Instant occurredAt, String operatorId) {
    }

    record PayrollFeedRow(
            String resultBusinessId, String employeeBusinessId, String periodBusinessId,
            java.time.LocalDate periodStartDate, String status, BigDecimal confirmedAmount,
            BigDecimal payableAmount, BigDecimal paidAmount, long rowVersion, Instant updatedAt) {
    }

    record AdjustmentRecord(
            String adjustmentBusinessId, String resultBusinessId, BigDecimal amountDelta,
            String referenceRunBusinessId, String reasonText, Instant occurredAt,
            String operatorId) {
    }

    record CostAdjustmentRecord(
            String adjustmentBusinessId, String resultBusinessId, BigDecimal amountDelta,
            Instant createdAt) {
    }

    record ReferenceRunRecord(
            String runBusinessId, String periodBusinessId, String employeeBusinessId,
            String runType, String fingerprint, String sourceVersionsJson,
            String referencedRunBusinessId, Instant generatedAt, String operatorId) {
    }

    record ReferenceLineRecord(
            String lineBusinessId, String runBusinessId, String lineType,
            String sourceBusinessId, String componentCode, Integer quantity,
            BigDecimal unitAmount, BigDecimal referenceAmount, boolean occurred,
            boolean estimated, String materialSource, boolean incomplete, String note,
            Instant occurredAt) {
    }
}

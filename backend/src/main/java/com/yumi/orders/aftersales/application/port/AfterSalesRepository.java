package com.yumi.orders.aftersales.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 售后案例持久化端口。 */
public interface AfterSalesRepository {

    void insertCase(CaseRecord record);

    void insertIssue(IssueRecord record);

    void insertObligation(ObligationRecord record);

    Optional<CaseRecord> findCase(String caseBusinessId);

    /** 按案例号或订单号模糊检索，可按状态过滤。 */
    List<CaseRecord> searchCases(
            String keyword, String status, int offset, int limit);

    Optional<CaseRecord> lockCase(String caseBusinessId);

    List<ObligationRecord> findObligations(String caseBusinessId);

    List<ObligationRecord> lockObligations(String caseBusinessId);

    void updateObligationStatus(
            String obligationBusinessId, String status, long expectedRowVersion);

    void insertAmountDecision(AmountDecisionRecord record);

    List<AmountDecisionRecord> findAmountDecisions(String caseBusinessId);

    List<AmountDecisionRecord> findAmountDecisionsByObligation(String obligationBusinessId);

    List<CaseDeliveryRecord> findDeliveriesOfCase(String caseBusinessId);

    List<CaseReturnRecord> findReturnsOfCase(String caseBusinessId);

    void insertCompletion(CompletionRecord record);

    Optional<CompletionRecord> findCompletion(String caseBusinessId);

    void markCaseCompleted(String caseBusinessId);

    Optional<ObligationRecord> lockObligation(String obligationBusinessId);

    Optional<ObligationRecord> findObligation(String obligationBusinessId);

    void updateObligationProgress(
            String obligationBusinessId, int fulfilledQuantity, BigDecimal fulfilledAmount,
            String status, long expectedRowVersion);

    void updateObligationTarget(
            String obligationBusinessId, Integer targetQuantity, BigDecimal targetAmount,
            String status, long expectedRowVersion);

    void insertObligationFact(ObligationFactRecord record);

    void insertProductionLink(ProductionLinkRecord record);

    Optional<ProductionLinkRecord> findProductionLink(String obligationBusinessId, String linkType);

    void insertPaymentLink(AfterSalesPaymentLinkRecord record);

    Optional<AfterSalesPaymentLinkRecord> findPaymentLink(String factBusinessId);

    Optional<AfterSalesPaymentLinkRecord> findPaymentLinkByCashFact(String financeCashFactBusinessId);

    Optional<AfterSalesPaymentLinkRecord> findPaymentLinkReversal(String originalFactId);

    record AfterSalesPaymentLinkRecord(
            String factBusinessId, String obligationBusinessId, String financeCashFactBusinessId,
            BigDecimal linkedAmount, String correctsFactBusinessId, Instant occurredAt,
            String operatorId) {
    }

    int maxCostSourceVersion(String sourceBusinessId);

    void insertCostSource(CostSourceRecord record);

    List<CostSourceRecord> listCostSourcesAfter(long afterSequence, int limit);

    /** 某案例全部费用来源，按发布顺序。 */
    List<CostSourceRecord> findCostSourcesByCase(String caseBusinessId);

    Optional<CostSourceRecord> findLatestCostSource(String sourceBusinessId);

    List<CaseRecord> findCasesChangedAfter(
            Instant updatedAfter, String afterCaseBusinessId, int limit);

    record CostSourceRecord(
            long feedSequence, String sourceBusinessId, int sourceVersion,
            String caseBusinessId, String orderBusinessId, String costCategory,
            BigDecimal amount, LocalDate businessDate, String operationType, String basisRef,
            String reasonText, Instant occurredAt) {
    }

    record ObligationFactRecord(
            String factBusinessId, String obligationBusinessId, String operationType,
            Integer quantity, BigDecimal amount, String quantityResolutionRefsJson, String note,
            Instant occurredAt, String operatorId) {
    }

    record ProductionLinkRecord(
            String linkBusinessId, String obligationBusinessId,
            String returnReceiptItemBusinessId, String productionDemandBusinessId,
            String productionRepairSourceBusinessId, String demandType, String linkType) {
    }

    void insertReceiptFact(ReceiptFactRecord record);

    void insertReceiptItem(ReceiptItemRecord record);

    Optional<ReceiptItemRecord> findReceiptItem(String receiptItemBusinessId);

    List<ReceiptItemRecord> findReceiptItems(String obligationBusinessId);

    /** 最近一次交付明细：普通发货或售后交付中时间最新的一条。 */
    Optional<DeliveryCandidate> findLatestDelivery(String orderItemBusinessId);

    /** 某交付明细已被有效退回的数量。 */
    int returnedQuantityOfDeliveryItem(String deliveryItemBusinessId);

    /** 某接收明细已处置的数量。 */
    int disposedQuantityOfReceiptItem(String receiptItemBusinessId);

    int receivedQuantityOfObligation(String obligationBusinessId);

    void insertDisposition(DispositionRecord record);

    Optional<DispositionRecord> findDisposition(String factBusinessId);

    record DispositionRecord(
            String factBusinessId, String obligationBusinessId, String receiptItemBusinessId,
            String productionReturnedSourceBusinessId, String dispositionType,
            String physicalDestination, String productionTerminalExitBusinessId, int quantity,
            String productionDispositionFactBusinessId, String productionConsumptionFactBusinessId,
            String productionOfflineTransferFactBusinessId, String reasonText,
            java.time.LocalDate businessDate, Instant occurredAt, String operatorId) {
    }

    void insertDeliveryFact(DeliveryFactRecord record);

    void insertDeliveryItem(DeliveryItemRecord record);

    Optional<DeliveryItemRecord> findDeliveryItem(String deliveryItemBusinessId);

    Optional<DeliveryFactRecord> findDeliveryFact(String deliveryFactBusinessId);

    record ReceiptFactRecord(
            String factBusinessId, String obligationBusinessId, int receivedTotalQuantity,
            String conditionCode, Instant receivedAt, String operatorId) {
    }

    record ReceiptItemRecord(
            String receiptItemBusinessId, String receiptFactBusinessId,
            String obligationBusinessId, String priorDeliveryType, String priorDeliveryBusinessId,
            String priorDeliveryItemBusinessId, String priorDeliverySourceType,
            String priorProductionSourceBusinessId, String priorReturnedSourceBusinessId,
            String physicalRootSourceBusinessId, String priorConsumptionFactBusinessId,
            String productionReturnedSourceBusinessId, int quantity) {
    }

    record DeliveryCandidate(
            String deliveryType, String deliveryBusinessId, String deliveryItemBusinessId,
            String sourceType, String productionSourceBusinessId, String returnedSourceBusinessId,
            String physicalRootSourceBusinessId, String consumptionFactBusinessId,
            int quantity, Instant deliveredAt) {
    }

    record DeliveryFactRecord(
            String factBusinessId, String obligationBusinessId, String orderItemBusinessId,
            String deliveryType, Instant deliveredAt, String recipientPayloadJson,
            String trackingNo, String operatorId) {
    }

    record DeliveryItemRecord(
            String deliveryItemBusinessId, String deliveryFactBusinessId,
            String deliverySourceType, String productionSourceBusinessId,
            String productionReturnedSourceBusinessId, String physicalRootSourceBusinessId,
            int quantity, String productionConsumptionFactBusinessId) {
    }

    record CaseRecord(
            String caseBusinessId, String caseNo, String orderBusinessId,
            String customerBusinessId, String issueSummary, String routeType, String status,
            Instant openedAt, Instant updatedAt, long contentVersion, long rowVersion) {
    }

    record IssueRecord(
            String factBusinessId, String caseBusinessId, String issueType, String severity,
            String description, int evidenceCount, Instant occurredAt) {
    }

    record ObligationRecord(
            String obligationBusinessId, String caseBusinessId, String orderItemBusinessId,
            String obligationType, String bearerType, String predecessorObligationBusinessId,
            String createdByChangeBusinessId, Integer targetQuantity, BigDecimal targetAmount,
            int fulfilledQuantity, BigDecimal fulfilledAmount,
            LocalDate dueDate, String note, String status, long rowVersion) {
    }

    record CaseReturnRecord(
            String receiptItemBusinessId, String obligationBusinessId, int quantity,
            String conditionCode, Instant receivedAt) {
    }

    record CaseDeliveryRecord(
            String deliveryFactBusinessId, String obligationBusinessId, String deliveryType,
            Instant deliveredAt, String trackingNo, int quantity) {
    }

    record AmountDecisionRecord(
            String decisionBusinessId, String caseBusinessId, String obligationBusinessId,
            BigDecimal amount, String note, Instant decidedAt, String operatorId) {
    }

    record CompletionRecord(
            String factBusinessId, String caseBusinessId, String factType,
            String obligationFingerprint, Instant occurredAt, String operatorId) {
    }
}

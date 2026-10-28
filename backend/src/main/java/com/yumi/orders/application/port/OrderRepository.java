package com.yumi.orders.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 订单持久化端口。 */
public interface OrderRepository {

    void insertOrder(OrderRecord record);

    void insertConfirmationFact(ConfirmationRecord record);

    void insertItem(ItemRecord record);

    void insertAgreementGroup(AgreementGroupRecord record);

    void insertAgreementVersion(AgreementVersionRecord record);

    void insertAgreementProcess(AgreementProcessRecord record);

    void insertReceivableFact(ReceivableRecord record);

    void insertFulfillmentProjection(ProjectionRecord record);

    Optional<OrderRecord> findOrder(String orderBusinessId);

    List<ItemRecord> findItems(String orderBusinessId);

    Optional<AgreementGroupRecord> findGroup(String orderItemBusinessId);

    Optional<AgreementGroupRecord> findGroupById(String agreementGroupBusinessId);

    /** 按固定锁序锁定约定组（订单根之后、生产来源之前）。 */
    void lockGroup(String agreementGroupBusinessId);

    Optional<AgreementVersionRecord> findActiveVersion(String groupBusinessId, int versionNo);

    List<AgreementProcessRecord> findProcesses(String agreementVersionBusinessId);

    Optional<ProjectionRecord> findProjection(String orderBusinessId);

    void updateOrderReceivable(
            String orderBusinessId, java.math.BigDecimal receivable, long expectedContentVersion);

    void updateItemActiveQuantity(String orderItemBusinessId, int activeQuantity, String status);

    void updateGroupActiveQuantity(
            String agreementGroupBusinessId, int activeQuantity, int activeVersionNo, String status);

    void insertChangeFact(ChangeRecord record);

    void insertChangeAction(ChangeActionRecord record);

    void insertOfflineTransferLink(
            String changeBusinessId, String groupBusinessId, String productionSourceBusinessId,
            String productionTransferBusinessId, int quantity);

    Optional<ItemRecord> findItem(String orderItemBusinessId);

    /** 订单项对应的生产需求（订单只保存稳定关联，不直接查询生产表）。 */
    String findProductionDemandIdOfItem(String orderItemBusinessId);

    /** 生产需求对应的履约来源。 */
    String findProductionSourceIdOfDemand(String demandBusinessId);

    /** 变更事实。 */
    record ChangeRecord(
            String changeBusinessId, String orderBusinessId, String changeType, String reasonText,
            BigDecimal amountDelta, Instant occurredAt, String operatorId) {
    }

    /** 变更动作。 */
    record ChangeActionRecord(
            String changeBusinessId, String sourceOrderItemBusinessId, String sourceGroupBusinessId,
            String successorOrderItemBusinessId, String successorGroupBusinessId,
            String sourceProductionDemandBusinessId, String successorProductionDemandBusinessId,
            String actionType, int migratedQuantity, int quantityBefore, int quantityAfter,
            int oldVersionNo, int newVersionNo) {
    }

    void insertShipment(ShipmentRecord record);

    void insertShipmentItem(ShipmentItemRecord record);

    void insertShipmentSourceLink(
            String shipmentBusinessId, String productionSourceBusinessId,
            String productionConsumptionFactBusinessId, int quantity);

    void insertShipmentCorrection(CorrectionRecord record);

    Optional<ShipmentRecord> findShipment(String shipmentBusinessId);

    Optional<ShipmentItemRecord> findShipmentItem(String shipmentItemBusinessId);

    java.util.List<ShipmentItemRecord> findShipmentItems(String shipmentBusinessId);

    /** 订单下全部发货明细（用于汇总已发数量）。 */
    java.util.List<ShipmentItemRecord> findShipmentItemsOfOrder(String orderBusinessId);

    int effectiveShipmentQuantity(String shipmentBusinessId);

    int effectiveShippedQuantityOfOrder(String orderBusinessId);

    void updateShipmentItemQuantity(String shipmentItemBusinessId, int quantity);

    void updateGroupFulfillment(
            String agreementGroupBusinessId, int fulfilledQuantity, String status);

    void lockOrder(String orderBusinessId);

    void updateOrderDeliveryFulfilled(
            String orderBusinessId, boolean deliveryFulfilled, boolean paymentFulfilled,
            long expectedContentVersion);

    /** 有效收款合计。 */
    BigDecimal receivedAmountOf(String orderBusinessId);

    /** 有效退款合计。 */
    BigDecimal refundedAmountOf(String orderBusinessId);

    /** 有效净收款 = 有效收款 − 有效退款。 */
    BigDecimal netReceivedAmount(String orderBusinessId);

    void insertPaymentLink(PaymentLinkRecord record);

    Optional<PaymentLinkRecord> findPaymentLink(String factBusinessId);

    Optional<PaymentLinkRecord> findPaymentLinkByCashFact(String financeCashFactBusinessId);

    Optional<PaymentLinkRecord> findReversalOf(String originalFactBusinessId);

    Optional<RefundObligationRecord> findRefundObligation(String orderBusinessId);

    void lockRefundObligation(String orderBusinessId);

    void insertRefundObligation(RefundObligationRecord record);

    void updateRefundObligation(
            String refundBusinessId, BigDecimal decidedAmount, BigDecimal paidAmount,
            String status);

    void insertCompletionFact(CompletionRecord record);

    Optional<CompletionRecord> findCompletion(String orderBusinessId);

    void markOrderCompleted(String orderBusinessId);

    void insertLegacyIntake(LegacyIntakeRecord record);

    void insertLegacyIntakeItem(
            String intakeBusinessId, String orderItemBusinessId, int intakeQuantity, String note);

    void insertLegacyStateLine(LegacyStateLineRecord record);

    /** 订单列表：订单号模糊检索，按创建倒序。 */
    /** 列表摘要附带客户名与商品名；客户/商品名称取当前主数据，不替代历史快照。 */
    List<OrderSummaryRecord> searchOrderSummaries(String keyword, String status, int offset, int limit);

    record OrderSummaryRecord(
            OrderRecord order, String customerName, String productNames,
            int requiredQuantity, int shippedQuantity) {
    }

    /** 订单工作区：发货头。 */
    List<ShipmentOfOrderRecord> findShipmentsOfOrder(String orderBusinessId);

    /** 订单工作区：发货明细（含订单项与有效数量）。 */
    List<ShipmentItemOfOrderRecord> findShipmentItemsOfOrderView(String orderBusinessId);

    /** 订单工作区：发货更正事实。 */
    List<CorrectionOfOrderRecord> findCorrectionsOfOrder(String orderBusinessId);

    /** 订单工作区：支付关联事实。 */
    List<PaymentLinkOfOrderRecord> findPaymentLinksOfOrder(String orderBusinessId);

    /** 订单工作区：确认事实。 */
    List<ConfirmationRecord> findConfirmationsOfOrder(String orderBusinessId);

    /** 订单工作区：变更事实。 */
    List<ChangeRecord> findChangesOfOrder(String orderBusinessId);

    /** 订单工作区：完成事实。 */
    List<CompletionRecord> findCompletionsOfOrder(String orderBusinessId);

    /** 报表变化：按 {@code (updated_at, order_business_id)} 键集分页。 */
    List<OrderRecord> findOrdersChangedAfter(
            Instant updatedAfter, String afterOrderBusinessId, int limit);

    /** 已有订单接管头。 */
    record LegacyIntakeRecord(
            String intakeBusinessId, String orderBusinessId, String originalOrderReference,
            String note, Instant occurredAt, String operatorId) {
    }

    /** 已有订单接管状态明细。 */
    record LegacyStateLineRecord(
            String intakeBusinessId, String orderItemBusinessId, String stageCode,
            String qualityCode, String dispositionCode, String physicalDestination,
            String reworkProcessCode, int quantity, String reasonText) {
    }

    /** 支付关联事实。 */
    record PaymentLinkRecord(
            String factBusinessId, String orderBusinessId, String financeCashFactBusinessId,
            String linkType, BigDecimal linkedAmount, String reversesFactBusinessId,
            String correctsFactBusinessId, Instant occurredAt, String operatorId) {
    }

    /** 订单工作区发货头。 */
    record ShipmentOfOrderRecord(
            String shipmentBusinessId, String shipmentNo, Instant shippedAt,
            String trackingNo, boolean valid) {
    }

    /** 订单工作区发货明细。 */
    record ShipmentItemOfOrderRecord(
            String shipmentItemBusinessId, String shipmentBusinessId, String orderItemBusinessId,
            String productionSourceBusinessId, int quantity, int effectiveQuantity) {
    }

    /** 订单工作区发货更正。 */
    record CorrectionOfOrderRecord(
            String factBusinessId, String shipmentBusinessId, String shipmentItemBusinessId,
            String correctionType, int quantityDelta, String reasonText, Instant occurredAt,
            boolean valid) {
    }

    /** 订单工作区支付关联。 */
    record PaymentLinkOfOrderRecord(
            String factBusinessId, String financeCashFactBusinessId, String linkType,
            BigDecimal linkedAmount, String reversesFactBusinessId, boolean valid,
            Instant occurredAt) {
    }

    /** 退款义务投影。 */
    record RefundObligationRecord(
            String refundBusinessId, String orderBusinessId, BigDecimal decidedAmount,
            BigDecimal paidAmount, String status, long rowVersion) {
    }

    /** 完成事实。 */
    record CompletionRecord(
            String factBusinessId, String orderBusinessId, String factType,
            String fulfillmentFingerprint, Instant occurredAt, String operatorId) {
    }

    /** 发货事实头。 */
    record ShipmentRecord(
            String shipmentBusinessId, String shipmentNo, String orderBusinessId,
            Instant shippedAt, String recipientPayloadJson, String trackingNo, String operatorId) {
    }

    /** 发货明细。 */
    record ShipmentItemRecord(
            String shipmentItemBusinessId, String shipmentBusinessId,
            String agreementGroupBusinessId, String agreementVersionBusinessId,
            String productionSourceBusinessId, String productionConsumptionFactBusinessId,
            int quantity) {
    }

    /** 发货更正事实。 */
    record CorrectionRecord(
            String factBusinessId, String shipmentBusinessId, String shipmentItemBusinessId,
            String correctionType, int quantityDelta, String reasonText, Instant occurredAt,
            String operatorId) {
    }

    /** 订单根记录。 */
    record OrderRecord(
            String orderBusinessId, String orderNo, String origin, String customerBusinessId,
            String customerSnapshotRef, LocalDate orderDate, LocalDate expectedDeliveryDate,
            String note, String status, BigDecimal currentReceivableAmount, BigDecimal netReceipts,
            String currencyCode, boolean deliveryFulfilled, boolean paymentFulfilled,
            long contentVersion, long rowVersion,
            Instant createdAt, String createdBy, Instant updatedAt) {
    }

    /** 确认事实。 */
    record ConfirmationRecord(
            String factBusinessId, String orderBusinessId, String confirmationType, int totalQuantity,
            BigDecimal totalAmount, Instant occurredAt, String operatorId) {
    }

    /** 订单项。 */
    record ItemRecord(
            String orderItemBusinessId, String orderBusinessId, int itemNo,
            String productBusinessId, String productSnapshotRef, int orderedQuantity,
            int activeQuantity, String status, long rowVersion) {
    }

    /** 交付约定组。 */
    record AgreementGroupRecord(
            String agreementGroupBusinessId, String orderItemBusinessId, int activeVersionNo,
            int activeQuantity, int fulfilledQuantity, String status, long rowVersion) {
    }

    /** 不可变交付约定版本。 */
    record AgreementVersionRecord(
            String agreementVersionBusinessId, String groupBusinessId, int versionNo,
            String schemeSnapshotRef, String targetProcess, BigDecimal unitPrice, int quantity,
            String glueSource, String colorPasteSource, String bagSource, String decorationSource,
            String productionBasisJson, String productionBasisFingerprint, String recipientPayloadJson,
            String expectedCostPayloadJson, String effectiveFactBusinessId, String contentFingerprint,
            Instant createdAt) {
    }

    /** 约定工序明细。 */
    record AgreementProcessRecord(
            String agreementVersionBusinessId, String processCode, int standardMinutes,
            BigDecimal laborCost, BigDecimal materialCost, BigDecimal otherCost, String sourceTermsJson) {
    }

    /** 应收事实。 */
    record ReceivableRecord(
            String factBusinessId, String orderBusinessId, String factType, BigDecimal amountDelta,
            String currencyCode, String sourceChangeBusinessId, Instant occurredAt) {
    }

    /** 履约投影。 */
    record ProjectionRecord(
            String orderBusinessId, int requiredQuantity, int shippedQuantity,
            BigDecimal currentReceivable, BigDecimal receivedAmount, BigDecimal refundedAmount,
            boolean deliveryFulfilled, boolean paymentFulfilled, String blockingReasonCodes,
            long rowVersion) {
    }
}

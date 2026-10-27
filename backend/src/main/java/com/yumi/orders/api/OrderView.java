package com.yumi.orders.api;

import java.time.LocalDate;
import java.util.List;

/** 订单视图：含订单项、交付约定版本与履约投影。 */
public record OrderView(
        String orderBusinessId,
        String orderNo,
        OrderOrigin origin,
        OrderStatus status,
        String customerBusinessId,
        String customerSnapshotRef,
        LocalDate orderDate,
        LocalDate expectedDeliveryDate,
        String note,
        com.yumi.platform.api.value.SettlementMoney currentReceivableAmount,
        boolean deliveryFulfilled,
        boolean paymentFulfilled,
        List<ItemView> items,
        FulfillmentView fulfillment) {

    public OrderView {
        items = List.copyOf(items);
    }

    /** 订单项视图。 */
    public record ItemView(
            String orderItemBusinessId,
            String productBusinessId,
            String productSnapshotRef,
            int orderedQuantity,
            int activeQuantity,
            String status,
            AgreementView agreement) {
    }

    /** 当前生效交付约定版本。 */
    public record AgreementView(
            String agreementGroupBusinessId,
            String agreementVersionBusinessId,
            int versionNo,
            String schemeSnapshotRef,
            String targetProcess,
            com.yumi.platform.api.value.SettlementMoney unitPrice,
            int quantity,
            int fulfilledQuantity,
            String productionBasisFingerprint,
            String glueSource,
            String colorPasteSource,
            String bagSource,
            String decorationSource,
            RecipientInfo recipient) {
    }

    /** 履约投影。 */
    public record FulfillmentView(
            int requiredQuantity,
            int shippedQuantity,
            com.yumi.platform.api.value.SettlementMoney currentReceivable,
            com.yumi.platform.api.value.SettlementMoney receivedAmount,
            com.yumi.platform.api.value.SettlementMoney refundedAmount,
            boolean deliveryFulfilled,
            boolean paymentFulfilled,
            List<String> blockingReasons) {

        public FulfillmentView {
            blockingReasons = List.copyOf(blockingReasons);
        }
    }
}

package com.yumi.orders.fulfillment;

/**
 * 履约视图（任务 3.6）：共同数量按工序分流展示，禁止按工序相加。
 * `makingRequired`/`packingRequired`/`finalRequired` 均为订购数量，`seamRequired` 为缝边数量，
 * `noSeamRequired` 为订购数量 − 缝边数量；最终交付需求始终是订购数量，不是订购数量 + 订购数量 + 缝边数量。
 */
public final class FulfillmentViews {

    private FulfillmentViews() {
    }

    /** 明细履约：需求分流 + 四层数量 + 派生状态。 */
    public record ItemFulfillment(
            long orderItemId,
            int lineNo,
            String productNo,
            String productName,
            int quantity,
            int seamQuantity,
            int noSeamRequired,
            int makingRequired,
            int packingRequired,
            int seamRequired,
            int finalRequired,
            int makingInflow,
            int packingInflow,
            int seamInflow,
            int makingPlanned,
            int packingPlanned,
            int seamPlanned,
            int verifiedProcessed,
            int reworkPending,
            int remakePending,
            int shippable,
            int shipped,
            int finishedSurplus,
            int undelivered,
            OrderStatuses.Derived derived) {
    }

    /** 订单履约视图：主状态 + 订单级派生状态 + 明细。 */
    public record FulfillmentView(
            long orderId,
            String orderNo,
            String status,
            OrderStatuses.Derived derived,
            java.util.List<ItemFulfillment> items) {
    }
}

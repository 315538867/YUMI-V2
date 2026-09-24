package com.yumi.orders.fulfillment;

/**
 * 履约视图（任务 3.6）：共同数量按工序分流展示，禁止按工序相加。
 * `makingRequired`/`packingRequired`/`finalRequired` 均为 Q，`seamRequired` 为 E，
 * `noSeamRequired` 为 Q − E；最终交付需求始终是 Q，不是 Q + Q + E。
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

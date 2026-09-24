package com.yumi.orders.fulfillment;

/**
 * 订单多维只读状态派生（任务 3.10，依据 `docs/architecture/domain-and-quantity-model.md` §13）：
 * 一个状态不表达所有含义，五类状态全部由事实与数量派生，不提供手工状态列。
 * 生产报废、计划创建与成品余量都**不**改变客户需求口径：需求处理与发货进度只看累计有效发货。
 */
public final class OrderStatuses {

    private OrderStatuses() {
    }

    /** 派生输入：当前有效需求、累计有效发货与各工序数量投影。 */
    public record Quantities(
            int required,
            int shipped,
            int makingInflow,
            int packingInflow,
            int seamInflow,
            int makingPlanned,
            int packingPlanned,
            int seamPlanned,
            int verifiedProcessed,
            int finishedSurplus) {
    }

    /** 五类派生状态。 */
    public record Derived(String scheduling, String execution, String production,
                          String demandHandling, String shipment) {
    }

    public static Derived derive(Quantities q) {
        int planned = q.makingPlanned() + q.packingPlanned() + q.seamPlanned();
        int executable = Math.max(0, q.makingInflow() - q.verifiedProcessed());
        String production = q.verifiedProcessed() == 0
                ? (planned > 0 ? "生产中" : "未开始")
                : (q.verifiedProcessed() < q.required() ? "部分完成" : "生产处理完成");
        return new Derived(
                planned > 0 ? "已排产" : "未排产",
                executable > 0 ? "可执行" : "等待上游",
                production,
                q.shipped() == 0 ? "仍有待履约" : (q.shipped() < q.required() ? "部分处理" : "全部处理"),
                q.shipped() == 0 ? "未发货" : (q.shipped() < q.required() ? "部分发货" : "全部发货"));
    }

}

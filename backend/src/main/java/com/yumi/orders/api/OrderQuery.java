package com.yumi.orders.api;

import java.util.List;

/** 订单只读查询。 */
public interface OrderQuery {

    OrderView get(String orderBusinessId);

    /** 交付履约投影，供完成判定与页面展示。 */
    OrderView.FulfillmentView getFulfillment(String orderBusinessId);

    /** 订单列表：按订单号模糊检索，可按状态过滤。 */
    List<OrderSummaryView> search(String keyword, OrderStatus status, int page, int size);

    /** 订单详情工作区：订单、发货、支付关联与时间线。 */
    OrderWorkspaceView workspace(String orderBusinessId);
}

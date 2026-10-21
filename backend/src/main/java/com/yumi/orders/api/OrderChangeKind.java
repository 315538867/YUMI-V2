package com.yumi.orders.api;

/** 订单变更分类：先分类再决定是否触碰生产定义。 */
public enum OrderChangeKind {
    /** 仅价格、收件人或收货地址变化：只追加约定版本。 */
    PRICE_OR_RECIPIENT,
    /** 仅数量变化：调整数量，不伪装成商品定义变更。 */
    QUANTITY,
    /** 商品、方案、目标工序或材料来源变化：建立继任订单项与新需求。 */
    PRODUCTION_DEFINITION,
    /** 整项取消：按项逐一执行与减量相同的校验。 */
    CANCELLATION
}

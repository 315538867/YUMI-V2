package com.yumi.product.api;

/** 商品与方案状态：停用只禁止新业务引用，不影响历史订单与快照解析。 */
public enum ProductStatus {
    ACTIVE,
    INACTIVE
}

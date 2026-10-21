package com.yumi.product.api;

/** 商品引用。 */
public record ProductRef(
        String productBusinessId, String productNo, ProductStatus status,
        long contentVersion, long rowVersion, int dailyCapacity) {
}

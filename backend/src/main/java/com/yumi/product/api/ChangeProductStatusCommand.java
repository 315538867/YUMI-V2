package com.yumi.product.api;

/** 启停商品；停用不删除历史，也不改变历史快照。 */
public record ChangeProductStatusCommand(
        String productBusinessId, ProductStatus targetStatus, long expectedRowVersion) {
}

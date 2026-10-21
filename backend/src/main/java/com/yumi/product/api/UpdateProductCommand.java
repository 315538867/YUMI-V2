package com.yumi.product.api;

/**
 * 修改商品命令。字段语义与创建相同，但携带期望行版本。
 *
 * <p>不接受目标工序字段：目标工序只存在于方案。
 */
public record UpdateProductCommand(
        String productBusinessId,
        CreateProductCommand definition,
        long expectedRowVersion) {
}

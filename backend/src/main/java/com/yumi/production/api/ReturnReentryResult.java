package com.yumi.production.api;

/** 退回重入结果。 */
public record ReturnReentryResult(
        String returnedSourceBusinessId,
        String reentryFactBusinessId,
        int reenteredQuantity,
        int availableQuantity) {
}

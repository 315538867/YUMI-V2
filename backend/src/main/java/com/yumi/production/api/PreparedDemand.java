package com.yumi.production.api;

import com.yumi.platform.api.value.ContentFingerprint;

/**
 * 已持锁校验的生产需求准备结果。
 *
 * <p>{@code prepareCreate} 只做只读校验；{@code commitCreate} 在订单确认事务内固化。
 */
public record PreparedDemand(
        CreateProductionDemandRequest request,
        String demandBusinessId,
        String productionSourceBusinessId,
        String physicalRootSourceBusinessId,
        ContentFingerprint basisFingerprint) {
}

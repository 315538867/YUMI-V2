package com.yumi.production.api;

/**
 * 消费准备结果：持锁校验后的可消费数量与来源上下文。
 *
 * @param preview {@code true} 表示只做校验；提交阶段需重新准备
 */
public record ConsumptionPreview(
        ProductionLinkRef delivery,
        String consumptionFactBusinessId,
        String productionSourceBusinessId,
        String physicalRootSourceBusinessId,
        String demandBusinessId,
        String productBusinessId,
        int quantity,
        int availableQuantity,
        boolean reverse,
        String reason) {
}

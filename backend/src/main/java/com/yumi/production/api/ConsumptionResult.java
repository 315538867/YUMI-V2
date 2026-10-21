package com.yumi.production.api;

/** 消费结果：消费事实与来源剩余可消费数量。 */
public record ConsumptionResult(
        String consumptionFactBusinessId,
        String productionSourceBusinessId,
        int quantity,
        int consumedQuantity,
        int availableToConsume,
        boolean reverse) {
}

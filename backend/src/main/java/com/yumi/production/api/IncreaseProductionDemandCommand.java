package com.yumi.production.api;

/** 已确认订单数量增量：只增加需求与来源初始数量，不生成任务。 */
public record IncreaseProductionDemandCommand(
        String demandBusinessId, int deltaQuantity, String reasonText) {

    public IncreaseProductionDemandCommand {
        if (demandBusinessId == null || demandBusinessId.isBlank()) {
            throw new IllegalArgumentException("生产需求标识不能为空");
        }
        if (deltaQuantity <= 0) {
            throw new IllegalArgumentException("增量必须大于 0");
        }
        if (reasonText == null || reasonText.isBlank()) {
            throw new IllegalArgumentException("增量必须填写原因");
        }
    }
}

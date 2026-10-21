package com.yumi.production.api;

/**
 * 消费生产来源命令（订单发货或售后交付在同一事务内调用）。
 *
 * <p>只消费已到达目标工序且仍由生产模块管理、未被预留或移交的数量；消费与交付事实同事务提交，
 * 交付失败不得留下消费。
 */
public record ConsumeProductionCommand(
        ProductionLinkRef delivery,
        String productionSourceBusinessId,
        int quantity) {

    public ConsumeProductionCommand {
        if (delivery == null) {
            throw new IllegalArgumentException("消费来源不能为空");
        }
        if (productionSourceBusinessId == null || productionSourceBusinessId.isBlank()) {
            throw new IllegalArgumentException("生产来源标识不能为空");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("消费数量必须大于 0");
        }
    }
}

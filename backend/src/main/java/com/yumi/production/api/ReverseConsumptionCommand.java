package com.yumi.production.api;

/**
 * 反向消费（发货更正或退回接收时冲销原消费）。
 *
 * <p>只能冲销仍然有效的消费；已移交线下的数量不能通过本命令取回。
 */
public record ReverseConsumptionCommand(
        ProductionLinkRef delivery,
        String productionSourceBusinessId,
        int quantity,
        String reason) {

    public ReverseConsumptionCommand {
        if (delivery == null) {
            throw new IllegalArgumentException("消费来源不能为空");
        }
        if (productionSourceBusinessId == null || productionSourceBusinessId.isBlank()) {
            throw new IllegalArgumentException("生产来源标识不能为空");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("反向数量必须大于 0");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("反向消费必须填写原因");
        }
    }
}

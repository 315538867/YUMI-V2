package com.yumi.production.api;

import java.util.List;

/**
 * 减量预检结果：每份数量归入唯一类别，并列出阻塞原因。
 *
 * @param cancelUnproducedQuantity 未制取消数量
 * @param transferProducedQuantity 已制移交数量
 * @param nonReducibleQuantity 不可减少数量（在制、已消费、无法解除的预留）
 * @param blockingReasons 阻塞原因；非空时整笔减量必须拒绝
 */
public record DemandReductionPreview(
        String demandBusinessId,
        String productionSourceBusinessId,
        int currentQuantity,
        int targetQuantity,
        int reductionQuantity,
        int cancelUnproducedQuantity,
        int transferProducedQuantity,
        int nonReducibleQuantity,
        List<String> blockingReasons) {

    public DemandReductionPreview {
        blockingReasons = List.copyOf(blockingReasons);
    }

    /** 是否允许提交。 */
    public boolean isAllowed() {
        return blockingReasons.isEmpty()
                && reductionQuantity == cancelUnproducedQuantity + transferProducedQuantity;
    }
}

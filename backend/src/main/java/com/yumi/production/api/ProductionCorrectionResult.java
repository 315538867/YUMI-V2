package com.yumi.production.api;

import java.util.List;

/**
 * 更正结果：新事实、批次项与投影变化。
 *
 * @param batchItems 本命令批次内的 REVERSE / APPLY 项；消费者按完整批次更新有效投影
 */
public record ProductionCorrectionResult(
        String correctionCommandBusinessId,
        String taskItemBusinessId,
        String commandFingerprint,
        int declaredItemCount,
        List<BatchItem> batchItems,
        VerificationView.ItemVerificationView correctedItem,
        boolean responsibilityReversedOnly) {

    public ProductionCorrectionResult {
        batchItems = List.copyOf(batchItems);
    }

    /** 批次内的来源项。 */
    public record BatchItem(
            int index, String itemKind, String effectType, String referencedFactBusinessId,
            int quantity, String businessDate) {
    }

    /** 批次是否完整：声明项数与实际项数一致且至少一项。 */
    public boolean isComplete() {
        return declaredItemCount > 0 && batchItems.size() == declaredItemCount;
    }
}

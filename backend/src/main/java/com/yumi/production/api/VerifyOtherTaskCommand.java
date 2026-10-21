package com.yumi.production.api;

/**
 * 其他任务核验命令（基线 11 §7.5）。
 *
 * <p>其他任务不产生工序流转、质量事实或生产数量事实；关联商品时
 * {@code 计划 = 完成 + 未完成}，只有完成数量生成劳动来源。
 *
 * @param completedQuantity 完成数量；未关联商品时为空
 * @param unfinishedQuantity 未完成数量；未关联商品时为空
 */
public record VerifyOtherTaskCommand(
        String taskBusinessId,
        Integer completedQuantity,
        Integer unfinishedQuantity,
        String resultNote) {

    public VerifyOtherTaskCommand {
        if (taskBusinessId == null || taskBusinessId.isBlank()) {
            throw new IllegalArgumentException("任务标识不能为空");
        }
        boolean hasQuantity = completedQuantity != null || unfinishedQuantity != null;
        if (hasQuantity && (completedQuantity == null || unfinishedQuantity == null)) {
            throw new IllegalArgumentException("完成与未完成数量必须同时给出");
        }
        if (hasQuantity && (completedQuantity < 0 || unfinishedQuantity < 0)) {
            throw new IllegalArgumentException("核验数量不能为负");
        }
    }
}

package com.yumi.production.api;

/**
 * 缩减某生产来源尚未核验的分配（订单减量/取消时由订单在同一事务调用）。
 *
 * <p>只调整当前订单来源：混合来源任务保留其他来源明细；剩余数量为零的明细转为取消，
 * 整任务剩余为零时任务追加取消事实。人员负载、商品新制产能与来源占用同步释放。
 */
public record ShrinkUnverifiedCommand(
        String productionSourceBusinessId,
        int quantity,
        String reasonCode,
        String reasonText) {

    public ShrinkUnverifiedCommand {
        if (productionSourceBusinessId == null || productionSourceBusinessId.isBlank()) {
            throw new IllegalArgumentException("生产来源标识不能为空");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("缩减数量必须大于 0");
        }
    }
}

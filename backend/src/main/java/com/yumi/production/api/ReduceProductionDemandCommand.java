package com.yumi.production.api;

import java.util.List;

/**
 * 订单减量/取消的生产侧请求（基线 11 §5.6）。
 *
 * <p>每一份数量必须归入唯一类别：未制取消或已制移交；已消费、在制与已预留但无法解除的数量
 * 属于不可减少部分，必须作为阻塞原因返回。
 *
 * @param targetQuantity 减量后的需求数量
 * @param transfers 已制数量的线下移交明细；未提供而存在已制数量时必须拒绝
 */
public record ReduceProductionDemandCommand(
        String demandBusinessId,
        int targetQuantity,
        List<TransferAllocation> transfers,
        String reasonCode,
        String reasonText) {

    public ReduceProductionDemandCommand {
        transfers = List.copyOf(transfers == null ? List.of() : transfers);
        if (demandBusinessId == null || demandBusinessId.isBlank()) {
            throw new IllegalArgumentException("生产需求标识不能为空");
        }
        if (targetQuantity < 0) {
            throw new IllegalArgumentException("减量后的需求数量不能为负");
        }
    }

    /** 已制数量的移交分配。 */
    public record TransferAllocation(String productionSourceBusinessId, int quantity) {

        public TransferAllocation {
            if (productionSourceBusinessId == null || productionSourceBusinessId.isBlank()) {
                throw new IllegalArgumentException("移交来源不能为空");
            }
            if (quantity <= 0) {
                throw new IllegalArgumentException("移交数量必须大于 0");
            }
        }
    }
}

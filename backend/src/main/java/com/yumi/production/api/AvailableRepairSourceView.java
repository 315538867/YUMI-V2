package com.yumi.production.api;

/**
 * 可安排返工来源：由生产核验中的返工处置生成，本次安排只能从剩余可用数量中占用。
 *
 * <p>返工工序固定为来源建立时记录的工序；返工任务不能改到其他工序执行。
 */
public record AvailableRepairSourceView(
        String repairSourceBusinessId,
        String originType,
        String demandBusinessId,
        String productionSourceBusinessId,
        DemandType demandType,
        String productBusinessId,
        String schemeBusinessId,
        TargetProcess targetProcess,
        TargetProcess repairProcessCode,
        int originalQuantity,
        int completedQuantity,
        int reservedQuantity,
        int availableQuantity) {
}

package com.yumi.production.api;

/**
 * 期初状态明细：只记录接管时数量所在阶段与实物去向，不构造历史任务或劳动。
 *
 * @param stageCode 阶段；工序阶段必须属于方案链且不晚于目标工序
 * @param physicalDestination 仅报废行必填实际去向；非报废行为系统内
 * @param reworkProcessCode 待返工行必填的返工工序
 * @param quantity 该状态数量
 * @param reasonText 报废或返工说明
 */
public record ProductionOpeningLine(
        OpeningStageCode stageCode,
        PhysicalDestination physicalDestination,
        TargetProcess reworkProcessCode,
        int quantity,
        String reasonText) {

    public ProductionOpeningLine {
        if (stageCode == null) {
            throw new IllegalArgumentException("期初状态必须指定阶段");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("期初状态数量必须大于零");
        }
        if (stageCode == OpeningStageCode.SCRAPPED) {
            if (physicalDestination != PhysicalDestination.DESTROYED
                    && physicalDestination != PhysicalDestination.TRANSFERRED_OFFLINE_INVENTORY) {
                throw new IllegalArgumentException("期初报废必须明确实际销毁或转线下");
            }
        } else if (physicalDestination != null
                && physicalDestination != PhysicalDestination.IN_SYSTEM) {
            throw new IllegalArgumentException("非报废期初数量必须在系统内");
        }
        if (stageCode == OpeningStageCode.REWORK_REQUIRED && reworkProcessCode == null) {
            throw new IllegalArgumentException("待返工数量必须明确返工工序");
        }
        if (stageCode != OpeningStageCode.REWORK_REQUIRED && reworkProcessCode != null) {
            throw new IllegalArgumentException("只有待返工数量可以指定返工工序");
        }
    }
}

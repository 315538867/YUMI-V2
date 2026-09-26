package com.yumi.calculation.production;

/**
 * 生产工序（阶段五 5.3）：与 `production` 模块的工序 code 一致，用于选择正常产能口径。
 * 制作按「工作日分钟 × 制品有效工时率」，包装与缝边剪袋按完整工作日分钟。
 */
public enum ProductionWorkType {

    MAKING,
    PACKING_BAG,
    SEAM_CUTTING;

    /** 工序 code → 计算口径；未知工序返回 null（调用方按校验失败处理，不猜测口径）。 */
    public static ProductionWorkType fromNode(String node) {
        if (node == null) {
            return null;
        }
        return switch (node) {
            case "MAKING" -> MAKING;
            case "PACKING_BAG" -> PACKING_BAG;
            case "SEAM_CUTTING" -> SEAM_CUTTING;
            default -> null;
        };
    }
}

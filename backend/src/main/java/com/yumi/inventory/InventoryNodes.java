package com.yumi.inventory;

import java.util.List;

/**
 * 库存工序节点与缝边状态，以及订单领用的接入矩阵（任务 4.5/4.7）。
 * 依据 `docs/architecture/domain-and-quantity-model.md` §8：
 * 制作合格库存 → 捏毛装袋；捏毛装袋合格库存 → 不缝边可发货 或 缝边剪袋；缝边剪袋合格库存 → 可发货。
 * 每笔来源只能接入一次（由领用明细与履约事实的唯一约束保证）。
 */
public final class InventoryNodes {

    public static final String MAKING = "MAKING";
    public static final String PACKING_BAG = "PACKING_BAG";
    public static final String SEAM_CUTTING = "SEAM_CUTTING";
    public static final String SHIPPABLE = "SHIPPABLE";

    public static final String SEAM_NONE = "NONE";
    public static final String SEAM_DONE = "DONE";

    private InventoryNodes() {
    }

    public static boolean isNode(String node) {
        return MAKING.equals(node) || PACKING_BAG.equals(node) || SEAM_CUTTING.equals(node)
                || SHIPPABLE.equals(node);
    }

    public static boolean isSeamState(String seamState) {
        return SEAM_NONE.equals(seamState) || SEAM_DONE.equals(seamState);
    }

    /** 该批次可接入的目标节点；空列表表示已是终态（可发货）不可再接入。 */
    public static List<String> allowedTargets(String node, String seamState) {
        return switch (node) {
            case MAKING -> List.of(PACKING_BAG);
            case PACKING_BAG -> SEAM_DONE.equals(seamState)
                    ? List.of(SHIPPABLE)
                    : List.of(SEAM_CUTTING, SHIPPABLE);
            case SEAM_CUTTING -> List.of(SHIPPABLE);
            default -> List.of();
        };
    }

    public static boolean canAllocate(String node, String seamState, String targetNode) {
        return allowedTargets(node, seamState).contains(targetNode);
    }

    /**
     * 工序与缝边状态的合法组合：制作/捏毛装袋是未缝边；缝边剪袋本身即“已缝边”；
     * 可发货既可能来自不缝边路径（NONE）也可能来自缝边路径（DONE）。
     */
    public static boolean isValidCombination(String node, String seamState) {
        if (node == null || !isNode(node) || !isSeamState(seamState)) {
            return false;
        }
        return switch (node) {
            case MAKING, PACKING_BAG -> SEAM_NONE.equals(seamState);
            case SEAM_CUTTING -> SEAM_DONE.equals(seamState);
            default -> true;
        };
    }

    /** 接入后的批次状态：缝边剪袋接入后即视为已缝边；不缝边进入可发货时保持原缝边状态。 */
    public static String seamStateAfter(String targetNode, String currentSeamState) {
        if (SEAM_CUTTING.equals(targetNode)) {
            return SEAM_DONE;
        }
        if (PACKING_BAG.equals(targetNode)) {
            return SEAM_NONE;
        }
        return currentSeamState;
    }
}

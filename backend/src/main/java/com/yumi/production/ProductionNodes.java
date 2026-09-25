package com.yumi.production;

import java.util.List;

/**
 * 生产工序与冻结流程（阶段五，依据 `domain-and-quantity-model.md` §5/§6/§7）：
 * 制作 → 捏毛装袋 → 缝边剪袋 → 可发货；返工只能回到「发现问题的工序或其前序」。
 * 与库存模块的接入矩阵是两套规则（那套管「库存接入到哪个工序」，本套管「核验后流到哪、返工回到哪」），
 * 因此不复用 `InventoryNodes`，也不让生产模块依赖库存模块。
 */
public final class ProductionNodes {

    public static final String MAKING = "MAKING";
    public static final String PACKING_BAG = "PACKING_BAG";
    public static final String SEAM_CUTTING = "SEAM_CUTTING";
    /** 最终可发货：不落计划表，只作为核验合格后的流入目标。 */
    public static final String SHIPPABLE = "SHIPPABLE";

    /** 工序顺序（前序在前）：返工矩阵与「前序」判定都基于它。 */
    private static final List<String> FLOW = List.of(MAKING, PACKING_BAG, SEAM_CUTTING);

    private ProductionNodes() {
    }

    public static boolean isNode(String node) {
        return FLOW.contains(node);
    }

    public static int indexOf(String node) {
        return FLOW.indexOf(node);
    }

    /** 返工允许的目标工序：发现问题工序本身或其前序（制作问题只能返工制作）。 */
    public static List<String> reworkTargets(String foundNode) {
        int index = indexOf(foundNode);
        return index < 0 ? List.of() : List.copyOf(FLOW.subList(0, index + 1));
    }

    public static boolean canRework(String foundNode, String targetNode) {
        return reworkTargets(foundNode).contains(targetNode);
    }

    /**
     * 核验合格后的流入节点；捏毛装袋返回 {@code null}，因为它要按冻结缝边数量分流到缝边剪袋与可发货，
     * 由服务层按 §4.3 计算，不能在这里给单一答案。
     */
    public static String qualifiedTarget(String node) {
        return switch (node) {
            case MAKING -> PACKING_BAG;
            case SEAM_CUTTING -> SHIPPABLE;
            default -> null;
        };
    }

    /** 工序总需求来源：缝边剪袋按冻结 E，其余按当前有效订购数量 Q。 */
    public static boolean usesSeamQuantity(String node) {
        return SEAM_CUTTING.equals(node);
    }
}

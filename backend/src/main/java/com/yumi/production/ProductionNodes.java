package com.yumi.production;

import java.util.List;

/**
 * 生产工序与冻结流程（阶段五，依据 `production-module-design.md` §2/§5）：
 * 制作 → 捏毛装袋 → 缝边剪袋 → 可发货。
 * 返工在**发生问题的工序内部**完成，不采用任何基于工序顺序的返工目标限制；
 * 返工合格后沿该工序的同一正常流向进入下一节点。
 * 与库存模块的接入矩阵是两套规则（那套管「库存接入到哪个工序」），因此不复用 `InventoryNodes`。
 */
public final class ProductionNodes {

    public static final String MAKING = "MAKING";
    public static final String PACKING_BAG = "PACKING_BAG";
    public static final String SEAM_CUTTING = "SEAM_CUTTING";
    /** 最终可发货：不落任务明细，只作为核验合格后的流入目标。 */
    public static final String SHIPPABLE = "SHIPPABLE";

    /** 工序顺序（前序在前）：产能汇总与前端展示都基于它。 */
    private static final List<String> FLOW = List.of(MAKING, PACKING_BAG, SEAM_CUTTING);

    private ProductionNodes() {
    }

    public static List<String> all() {
        return FLOW;
    }

    public static boolean isNode(String node) {
        return FLOW.contains(node);
    }

    /**
     * 核验合格后的流入节点；捏毛装袋返回 {@code null}，因为它要按冻结缝边数量分流到缝边剪袋与可发货，
     * 由核验服务按 §5.5 计算，不能在这里给单一答案。
     */
    public static String qualifiedTarget(String node) {
        return switch (node) {
            case MAKING -> PACKING_BAG;
            case SEAM_CUTTING -> SHIPPABLE;
            default -> null;
        };
    }

    /** 工序总需求来源：缝边剪袋按冻结的缝边数量，其余按订购数量。 */
    public static boolean usesSeamQuantity(String node) {
        return SEAM_CUTTING.equals(node);
    }
}

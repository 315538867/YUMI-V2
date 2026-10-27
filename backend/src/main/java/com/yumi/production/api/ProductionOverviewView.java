package com.yumi.production.api;

import java.util.List;

/**
 * 生产整体进度。
 *
 * <p>口径：
 * <ul>
 *   <li>需求：未取消需求的 requiredQuantity 合计；</li>
 *   <li>已排班：首个工序（制作/捏毛装袋/缝边剪袋按目标工序确定）未取消任务的计划量，按需求封顶；</li>
 *   <li>生产事实：首个工序已处理完成数量（含返工与报废），按需求封顶；</li>
 *   <li>已履约：订单项投影的 fulfilledQuantity 合计；</li>
 *   <li>待完成：需求 - 已履约（含在制、目标就绪与缺口）。</li>
 * </ul>
 */
public record ProductionOverviewView(
        int orderCount,
        int requiredQuantity,
        int scheduledQuantity,
        int productionFactQuantity,
        int fulfilledQuantity,
        int remainingQuantity,
        List<ProcessProgress> processes) {

    public ProductionOverviewView {
        processes = List.copyOf(processes);
    }

    /** 单道工序进度；需求只统计经过该工序的目标链。 */
    public record ProcessProgress(
            TargetProcess process,
            int requiredQuantity,
            int scheduledQuantity,
            int completedQuantity,
            int remainingQuantity) {
    }
}

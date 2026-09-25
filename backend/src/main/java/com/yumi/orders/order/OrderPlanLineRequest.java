package com.yumi.orders.order;

/**
 * 草稿库存计划行（任务 4.8）：明细序号 + 批次 + 计划数量。
 * 用 {@code lineNo} 而不是明细 id：草稿明细整单替换，明细 id 每次保存都会变，序号才稳定。
 * 计划只是参考、不占用库存；接入节点（制作/捏毛装袋/缝边剪袋/可发货）在实际领用时按接入矩阵显式选择。
 */
public record OrderPlanLineRequest(
        Integer lineNo,
        Long batchId,
        Integer quantity) {
}

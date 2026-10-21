package com.yumi.calculation.api;

import java.util.Objects;

/**
 * 商品单件预算明细。
 *
 * <p>每条明细有且只有一个承担方；人工、提成与商品额外单件费用固定由工作室承担。
 *
 * @param lineCode 稳定明细代码，用于成本归集与更正
 * @param label 面向用户的名称
 * @param nature 成本性质
 * @param bearer 承担方
 * @param procedure 归属工序；额外费用为空
 * @param fullAmount 单件全资源金额（4 位精度）
 */
public record BudgetLine(
        String lineCode,
        String label,
        CostNature nature,
        CostBearer bearer,
        WorkProcedure procedure,
        com.yumi.platform.api.value.CostAmount fullAmount) {

    public BudgetLine {
        Objects.requireNonNull(lineCode, "明细代码不能为空");
        Objects.requireNonNull(fullAmount, "明细金额不能为空");
        if (nature == CostNature.LABOR && bearer != CostBearer.STUDIO) {
            throw new IllegalArgumentException("人工与提成固定由工作室承担");
        }
        if (nature == CostNature.EXTRA_PIECE_FEE && bearer != CostBearer.STUDIO) {
            throw new IllegalArgumentException("商品额外单件费用固定由工作室承担");
        }
    }
}

package com.yumi.calculation.api;

/**
 * 商品单件预算计算（纯函数，无状态）。
 *
 * <p>规则来源：`docs/一期/07-计算中心模块.md` §4.2。实现要求：
 * <ul>
 *   <li>胶水用量只应用一次损耗；色浆直接使用含损耗的胶水用量；</li>
 *   <li>明细按目标工序并入，商品额外单件费用只加一次；</li>
 *   <li>中间值不提前舍入，明细在输出边界按 {@code HALF_UP} 舍入到 4 位；</li>
 *   <li>售价为零时利润率返回空；预计利润允许为负。</li>
 * </ul>
 */
public interface ProductBudgetCalculator {

    ProductBudgetResult calculate(ProductBudgetInput input);

    /** 便捷入口：直接构造实现，便于无状态调用与测试。 */
    static ProductBudgetCalculator create() {
        return new com.yumi.calculation.ProductBudgetCalculatorImpl();
    }
}

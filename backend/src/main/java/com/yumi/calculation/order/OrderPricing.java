package com.yumi.calculation.order;

import com.yumi.calculation.DecimalPolicy;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单计价纯函数（任务 3.3；目录 FP-ORDER-01..09）：
 * 明细商品金额=单价×数量、明细缝边收费=收费单价×缝边数量、明细商品成本=商品单件成本×数量、
 * 明细缝边成本=种类成本单价×缝边数量；订单应收=商品金额+缝边收费−整单优惠，
 * 订单总成本=商品成本+缝边成本，订单利润=应收−总成本（允许为负）。
 * 优惠只作用于应收，不进入成本；商品成本取不缝边剪袋口径的单件成本快照。
 * 不访问数据库、实体、HTTP、当前时间或登录上下文。
 */
public final class OrderPricing {

    private OrderPricing() {
    }

    /** 明细计价输入：单价与数量均非负；不缝边剪袋时缝边单价与数量按 0 传入。 */
    public record ItemInputs(
            BigDecimal unitPrice,
            int quantity,
            BigDecimal seamFee,
            int seamQuantity,
            BigDecimal seamUnitCost,
            BigDecimal unitCost) {
    }

    /** 明细计价结果（均为 scale4）。 */
    public record ItemResult(
            BigDecimal goodsAmount,
            BigDecimal seamAmount,
            BigDecimal goodsCostAmount,
            BigDecimal seamCostAmount) {
    }

    /** 订单汇总（均为 scale4）。 */
    public record OrderTotals(
            BigDecimal goodsAmount,
            BigDecimal seamAmount,
            BigDecimal discountAmount,
            BigDecimal receivableAmount,
            BigDecimal goodsCostAmount,
            BigDecimal seamCostAmount,
            BigDecimal costAmount,
            BigDecimal profitAmount) {
    }

    /** 明细计价：FP-ORDER-01..04（入参由边界归一为 scale4，公式只对乘积舍入）。 */
    public static ItemResult item(ItemInputs in) {
        BigDecimal goodsAmount = DecimalPolicy.money(
                value(in.unitPrice()).multiply(BigDecimal.valueOf(in.quantity())));
        BigDecimal seamAmount = DecimalPolicy.money(
                value(in.seamFee()).multiply(BigDecimal.valueOf(in.seamQuantity())));
        BigDecimal goodsCostAmount = DecimalPolicy.money(
                value(in.unitCost()).multiply(BigDecimal.valueOf(in.quantity())));
        BigDecimal seamCostAmount = DecimalPolicy.money(
                value(in.seamUnitCost()).multiply(BigDecimal.valueOf(in.seamQuantity())));
        return new ItemResult(goodsAmount, seamAmount, goodsCostAmount, seamCostAmount);
    }

    /** 订单汇总：FP-ORDER-05..09。优惠必须非负且不超过商品金额与缝边收费之和。 */
    public static OrderTotals totals(List<ItemResult> items, BigDecimal discountAmount) {
        BigDecimal goodsAmount = DecimalPolicy.money(sum(items, ItemResult::goodsAmount));
        BigDecimal seamAmount = DecimalPolicy.money(sum(items, ItemResult::seamAmount));
        BigDecimal goodsCostAmount = DecimalPolicy.money(sum(items, ItemResult::goodsCostAmount));
        BigDecimal seamCostAmount = DecimalPolicy.money(sum(items, ItemResult::seamCostAmount));
        BigDecimal discount = DecimalPolicy.money(discountAmount == null ? BigDecimal.ZERO : discountAmount);
        BigDecimal original = goodsAmount.add(seamAmount);
        if (discount.signum() < 0 || discount.compareTo(original) > 0) {
            throw new IllegalArgumentException("整单优惠必须在 0 与商品金额加缝边收费之间");
        }
        BigDecimal receivable = DecimalPolicy.money(original.subtract(discount));
        BigDecimal cost = DecimalPolicy.money(goodsCostAmount.add(seamCostAmount));
        BigDecimal profit = DecimalPolicy.money(receivable.subtract(cost));
        return new OrderTotals(goodsAmount, seamAmount, discount, receivable,
                goodsCostAmount, seamCostAmount, cost, profit);
    }

    private static BigDecimal sum(List<ItemResult> items, java.util.function.Function<ItemResult, BigDecimal> getter) {
        return items.stream().map(getter).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** null 视为 0（不缝边剪袋时缝边单价按 0 传入）。 */
    private static BigDecimal value(BigDecimal raw) {
        return raw == null ? BigDecimal.ZERO : raw;
    }
}

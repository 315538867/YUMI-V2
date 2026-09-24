package com.yumi.calculation.product;

import com.yumi.calculation.DecimalPolicy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 商品计价纯函数（任务 2.3 公式钉死，2.14 迁入集中计算模块；目录 FP-PROD-02..08、11..17、20..21）：
 * glueGrams=round(weight*(1+loss))；胶水/色浆=克重*单价(4)；
 * qty8=floor(480/std)、qty6=floor(360/std)、星级人工=120/qty6(4)（qty6=0 → 0）；
 * 包装=档位标准分钟*0.25+商品包装提成(4)；total=material+labor+other(4)；参考售价=total/0.7(4)；
 * 派生利润=sale−total(4)、毛利率=(sale−total)/sale(6)（sale=0 → 0）。
 * 缝边决策权在订单，商品只提供默认值：商品自身总成本按不缝边剪袋口径，缝边剪袋变体用
 * {@link #seamBudget} 单列展示（不缝边剪袋总成本 + 种类成本单价），不进入商品快照。
 * 不访问数据库、实体、HTTP、当前时间或登录上下文。
 */
public final class ProductPricing {

    private static final BigDecimal MINUTE_RATE = new BigDecimal("0.25");
    private static final BigDecimal MARGIN_DIVISOR = new BigDecimal("0.7");
    private static final BigDecimal LABOR_BASE = new BigDecimal("120");
    private static final int HOURS_8H = 480;
    private static final int MINUTES_6H = 360;
    private static final int MONEY_SCALE = 4;
    private static final int RATIO_SCALE = 6;

    private ProductPricing() {
    }

    public record Inputs(
            int weightG,
            BigDecimal lossRate,
            int stdMinutes,
            BigDecimal tierStdMinutes,
            BigDecimal packagingCommission,
            BigDecimal glueUnitPrice,
            BigDecimal colorpasteUnitPrice,
            BigDecimal boxLaborFee,
            BigDecimal transportPackingFee,
            BigDecimal dailySundriesFee,
            BigDecimal rentUtilitiesFee,
            BigDecimal moldAmortFee,
            BigDecimal salePrice) {
    }

    public record Result(
            int glueGrams,
            BigDecimal glueCost,
            BigDecimal colorpasteCost,
            int qty8h,
            int qty6h,
            BigDecimal productLaborFee,
            BigDecimal packagingLaborFee,
            BigDecimal materialCost,
            BigDecimal laborCost,
            BigDecimal otherCost,
            BigDecimal totalCost,
            BigDecimal referencePrice,
            BigDecimal estimatedProfit,
            BigDecimal estimatedMarginRate) {
    }

    /** 缝边剪袋变体预算（单件口径，FP-PROD-20/21）：商品自身快照不含缝边，变体只用于展示。 */
    public record SeamBudget(
            BigDecimal seamUnitCost,
            BigDecimal seamFee,
            BigDecimal totalCost,
            BigDecimal referencePrice) {
    }

    public static Result compute(Inputs in) {
        int glueGrams = DecimalPolicy.wholeNumber(
                BigDecimal.valueOf(in.weightG()).multiply(BigDecimal.ONE.add(in.lossRate())));
        BigDecimal grams = BigDecimal.valueOf(glueGrams);
        BigDecimal glueCost = DecimalPolicy.money(grams.multiply(in.glueUnitPrice()));
        BigDecimal colorpasteCost = DecimalPolicy.money(grams.multiply(in.colorpasteUnitPrice()));

        int std = in.stdMinutes();
        int qty8 = std <= 0 ? 0 : HOURS_8H / std;
        int qty6 = std <= 0 ? 0 : MINUTES_6H / std;
        BigDecimal productLaborFee = qty6 == 0
                ? DecimalPolicy.money(BigDecimal.ZERO)
                : LABOR_BASE.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                        .divide(BigDecimal.valueOf(qty6), MONEY_SCALE, RoundingMode.HALF_UP);

        BigDecimal packagingLaborFee = in.tierStdMinutes() == null
                ? DecimalPolicy.money(BigDecimal.ZERO)
                : DecimalPolicy.money(in.tierStdMinutes().multiply(MINUTE_RATE)
                        .add(in.packagingCommission() == null ? BigDecimal.ZERO : in.packagingCommission()));

        BigDecimal material = DecimalPolicy.money(glueCost.add(colorpasteCost));
        BigDecimal labor = DecimalPolicy.money(productLaborFee.add(packagingLaborFee).add(in.boxLaborFee()));
        BigDecimal other = DecimalPolicy.money(in.transportPackingFee().add(in.dailySundriesFee())
                .add(in.rentUtilitiesFee()).add(in.moldAmortFee()));
        BigDecimal total = DecimalPolicy.money(material.add(labor).add(other));
        BigDecimal referencePrice = referencePrice(total);

        BigDecimal profit = estimatedProfit(in.salePrice(), total);
        BigDecimal margin = estimatedMarginRate(in.salePrice(), total);

        return new Result(glueGrams, glueCost, colorpasteCost, qty8, qty6, productLaborFee,
                packagingLaborFee, material, labor, other, total, referencePrice, profit, margin);
    }

    /**
     * 缝边剪袋变体预算（FP-PROD-20/21，单件口径）：总成本 = 不缝边剪袋总成本 + 缝边种类成本单价；
     * 参考售价 = 变体总成本 ÷ 0.7。缝边价格按商品填写的收费单价原样回传，不参与成本。
     */
    public static SeamBudget seamBudget(BigDecimal productTotalCost, BigDecimal seamUnitCost, BigDecimal seamFee) {
        BigDecimal unitCost = DecimalPolicy.money(seamUnitCost == null ? BigDecimal.ZERO : seamUnitCost);
        BigDecimal fee = DecimalPolicy.money(seamFee == null ? BigDecimal.ZERO : seamFee);
        BigDecimal total = DecimalPolicy.money(productTotalCost.add(unitCost));
        return new SeamBudget(unitCost, fee, total, referencePrice(total));
    }

    /** 读时派生：参考售价 = 成本 ÷ 0.7（scale4）。 */
    public static BigDecimal referencePrice(BigDecimal totalCost) {
        return DecimalPolicy.money(totalCost.divide(MARGIN_DIVISOR, MONEY_SCALE, RoundingMode.HALF_UP));
    }

    /** 读时派生：estimatedProfit = sale − total（scale4）。 */
    public static BigDecimal estimatedProfit(BigDecimal salePrice, BigDecimal totalCost) {
        return DecimalPolicy.money(salePrice.subtract(totalCost));
    }

    /** 读时派生：毛利率 = (sale−total)/sale（scale6），sale=0 → 0.000000 防除零。 */
    public static BigDecimal estimatedMarginRate(BigDecimal salePrice, BigDecimal totalCost) {
        if (salePrice.signum() == 0) {
            return BigDecimal.ZERO.setScale(RATIO_SCALE, RoundingMode.HALF_UP);
        }
        return salePrice.subtract(totalCost)
                .divide(salePrice, RATIO_SCALE, RoundingMode.HALF_UP)
                .setScale(RATIO_SCALE, RoundingMode.HALF_UP);
    }
}

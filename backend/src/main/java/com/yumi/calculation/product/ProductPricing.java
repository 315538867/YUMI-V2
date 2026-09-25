package com.yumi.calculation.product;

import com.yumi.calculation.DecimalPolicy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 商品计价纯函数（任务 2.3 公式钉死，2.14 迁入集中计算模块；目录 FP-PROD-02..08、11..17、20..21）：
 * glueGrams=round(weight*(1+loss))；胶水/色浆=克重*单价(4)；
 * 工作日标准数量=floor(工作日小时数*60/std)；有效工时产量=floor(工作日小时数*60*制品有效工时率/std)；
 * 星级人工=(时薪*工作日小时数)/有效工时产量(4)（产量=0 → 0）；
 * 包装=档位整数分钟*(时薪/60)+商品包装提成(4)；total=material+labor+other(4)；
 * 参考售价=total/(1−目标利润率)(4)（目标利润率来自全局设置）；
 * 派生利润=sale−total(4)、毛利率=(sale−total)/sale(6)（sale=0 → 0）。
 * 三类工序人工费统一由全局时薪派生：制品按「时薪×工作日小时数 ÷ 有效工时产量」（工作日小时数与
 * 制品有效工时率都来自全局设置；制品工序不可能排满整个工作日，故按有效工时率折算的产量计标准产量）；
 * 包装与缝边按「标准分钟 × 时薪 ÷ 60」。
 * 缝边决策权在订单，商品只提供默认值：商品自身总成本按不缝边剪袋口径，缝边剪袋变体用
 * {@link #seamBudget} 单列展示（不缝边剪袋总成本 + 单件缝边人工成本），不进入商品快照。
 * 不访问数据库、实体、HTTP、当前时间或登录上下文。
 */
public final class ProductPricing {

    private static final BigDecimal MINUTES_PER_HOUR = new BigDecimal("60");
    private static final int MONEY_SCALE = 4;
    private static final int RATIO_SCALE = 6;
    private static final int RATE_SCALE = 8;

    private ProductPricing() {
    }

    public record Inputs(
            int weightG,
            BigDecimal lossRate,
            int stdMinutes,
            Integer tierStdMinutes,
            BigDecimal packagingCommission,
            BigDecimal hourlyWage,
            BigDecimal workdayHours,
            BigDecimal makingEffectiveHourRate,
            BigDecimal targetMarginRate,
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
        BigDecimal workday = workdayHours(in.workdayHours());
        int workdayMinutes = workday.multiply(MINUTES_PER_HOUR).setScale(0, RoundingMode.FLOOR).intValue();
        int qty8 = std <= 0 ? 0 : workdayMinutes / std;
        int effectiveMinutes = in.makingEffectiveHourRate() == null
                ? 0
                : BigDecimal.valueOf(workdayMinutes).multiply(in.makingEffectiveHourRate())
                        .setScale(0, RoundingMode.FLOOR).intValue();
        int qty6 = std <= 0 ? 0 : effectiveMinutes / std;
        BigDecimal productLaborFee = qty6 == 0
                ? DecimalPolicy.money(BigDecimal.ZERO)
                : DecimalPolicy.money(hourlyWage(in.hourlyWage()).multiply(workday))
                        .divide(BigDecimal.valueOf(qty6), MONEY_SCALE, RoundingMode.HALF_UP);

        BigDecimal packagingLaborFee = in.tierStdMinutes() == null
                ? DecimalPolicy.money(BigDecimal.ZERO)
                : DecimalPolicy.money(BigDecimal.valueOf(in.tierStdMinutes())
                        .multiply(minuteRate(in.hourlyWage()))
                        .add(in.packagingCommission() == null ? BigDecimal.ZERO : in.packagingCommission()));

        BigDecimal material = DecimalPolicy.money(glueCost.add(colorpasteCost));
        BigDecimal labor = DecimalPolicy.money(productLaborFee.add(packagingLaborFee).add(in.boxLaborFee()));
        BigDecimal other = DecimalPolicy.money(in.transportPackingFee().add(in.dailySundriesFee())
                .add(in.rentUtilitiesFee()).add(in.moldAmortFee()));
        BigDecimal total = DecimalPolicy.money(material.add(labor).add(other));
        BigDecimal referencePrice = referencePrice(total, in.targetMarginRate());

        BigDecimal profit = estimatedProfit(in.salePrice(), total);
        BigDecimal margin = estimatedMarginRate(in.salePrice(), total);

        return new Result(glueGrams, glueCost, colorpasteCost, qty8, qty6, productLaborFee,
                packagingLaborFee, material, labor, other, total, referencePrice, profit, margin);
    }

    /** 每分钟时薪 = 时薪 ÷ 60（scale8 HALF_UP）；包装与缝边的单件人工费基数。 */
    public static BigDecimal minuteRate(BigDecimal hourlyWage) {
        return hourlyWage(hourlyWage).divide(MINUTES_PER_HOUR, RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** 单件缝边人工成本 = 缝边标准分钟 × 每分钟时薪（scale4 HALF_UP）。 */
    public static BigDecimal seamUnitCost(Integer seamStdMinutes, BigDecimal hourlyWage) {
        if (seamStdMinutes == null) {
            return DecimalPolicy.money(BigDecimal.ZERO);
        }
        return DecimalPolicy.money(BigDecimal.valueOf(seamStdMinutes).multiply(minuteRate(hourlyWage)));
    }

    private static BigDecimal hourlyWage(BigDecimal hourlyWage) {
        return hourlyWage == null ? BigDecimal.ZERO : hourlyWage;
    }

    private static BigDecimal workdayHours(BigDecimal workdayHours) {
        return workdayHours == null ? BigDecimal.ZERO : workdayHours;
    }

    /**
     * 缝边剪袋变体预算（FP-PROD-20/21，单件口径）：总成本 = 不缝边剪袋总成本 + 单件缝边人工成本；
     * 参考售价 = 变体总成本 ÷ 0.7。缝边价格按商品填写的收费单价原样回传，不参与成本。
     */
    public static SeamBudget seamBudget(BigDecimal productTotalCost, BigDecimal seamUnitCost, BigDecimal seamFee,
                                        BigDecimal targetMarginRate) {
        BigDecimal unitCost = DecimalPolicy.money(seamUnitCost == null ? BigDecimal.ZERO : seamUnitCost);
        BigDecimal fee = DecimalPolicy.money(seamFee == null ? BigDecimal.ZERO : seamFee);
        BigDecimal total = DecimalPolicy.money(productTotalCost.add(unitCost));
        return new SeamBudget(unitCost, fee, total, referencePrice(total, targetMarginRate));
    }

    /**
     * 读时派生：参考售价 = 成本 ÷ (1 − 目标利润率)（scale4）。目标利润率为 0–1 比例，来自全局设置；
     * 缺省或 ≥ 1（配置异常）时按 0 处理，避免除零。
     */
    public static BigDecimal referencePrice(BigDecimal totalCost, BigDecimal targetMarginRate) {
        BigDecimal rate = targetMarginRate == null ? BigDecimal.ZERO : targetMarginRate;
        BigDecimal divisor = BigDecimal.ONE.subtract(rate);
        if (divisor.signum() <= 0) {
            divisor = BigDecimal.ONE;
        }
        return DecimalPolicy.money(totalCost.divide(divisor, MONEY_SCALE, RoundingMode.HALF_UP));
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

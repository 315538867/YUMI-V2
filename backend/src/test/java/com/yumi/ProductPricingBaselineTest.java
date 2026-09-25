package com.yumi;

import com.yumi.calculation.DecimalPolicy;
import com.yumi.calculation.product.ProductPricing;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务 2.13 公式基线（目录标识 FP-PROD-01..19、20..21）：人工核算的字符串算例，不启动 Spring，
 * 直接调用纯计算入口。2.14 迁入 calculation 后本类原样复用，作为“基准逐字段不变”的回归证据。
 */
class ProductPricingBaselineTest {

    @Test
    void lossRateConversionUsesScale6HalfUp() {
        assertThat(DecimalPolicy.percentToRatio(new BigDecimal("33.3333")).toPlainString()).isEqualTo("0.333333");
        assertThat(DecimalPolicy.percentToRatio(new BigDecimal("20.0005")).toPlainString()).isEqualTo("0.200005");
        // 0.00005/100 = 0.0000005 → scale6 HALF_UP 进位（HALF_EVEN 会得 0.000000）
        assertThat(DecimalPolicy.percentToRatio(new BigDecimal("0.00005")).toPlainString()).isEqualTo("0.000001");
    }

    @Test
    void glueGramsRoundsHalfUpAtPointFiveBoundary() {
        // 105 × (1+0.5) = 157.5 → 进位 158
        assertThat(input().weight(105).lossRate("0.5").compute().glueGrams()).isEqualTo(158);
        // 105 × 1.499999 = 157.499895 → 舍去 157
        assertThat(input().weight(105).lossRate("0.499999").compute().glueGrams()).isEqualTo(157);
    }

    @Test
    void nonDivisibleStandardMinutesFloorBeforeLaborFee() {
        // std=7：480/7=68.57→68，360/7=51.43→51，120/51=2.352941…→2.3529
        var result = input().star(7).salePrice("10").compute();

        assertThat(result.qty8h()).isEqualTo(68);
        assertThat(result.qty6h()).isEqualTo(51);
        plain(result.productLaborFee(), "2.3529");
        plain(result.laborCost(), "2.3529");
        plain(result.totalCost(), "2.3529");
        plain(result.referencePrice(), "3.3613");
        plain(result.estimatedProfit(), "7.6471");
        plain(result.estimatedMarginRate(), "0.764710");
    }

    @Test
    void workdayHoursDrivesDayWageAndWorkdayOutput() {
        // 工作日 10 小时、制品有效工时率 0.6：工作日标准数量 floor(600÷7)=85、有效工时产量 floor(600×0.6÷7)=51；
        // 星级人工 = (15 × 10) ÷ 51 = 2.9412（≠「工作日 8 小时 + 率 0.75」的 2.3529：日薪按 10 小时算）
        var result = input().star(7).workdayHours("10").effectiveHourRate("0.6").compute();

        assertThat(result.qty8h()).isEqualTo(85);
        assertThat(result.qty6h()).isEqualTo(51);
        plain(result.productLaborFee(), "2.9412");
    }

    @Test
    void hourlyWageAndEffectiveHourRateDriveLaborFees() {
        // 时薪 16、制品有效工时率 0.5：有效工时产量 = floor(480×0.5÷10) = 24；星级人工 = (16×8)÷24 = 5.3333
        // 包装 9 分钟：9 × (16÷60) = 2.4000 + 提成 0.3 = 2.7000
        var result = input().star(10).hourlyWage("16").effectiveHourRate("0.5").tier("9", "0.3").compute();

        assertThat(result.qty6h()).isEqualTo(24);
        plain(result.productLaborFee(), "5.3333");
        plain(result.packagingLaborFee(), "2.7000");
        plain(result.laborCost(), "8.0333");
    }

    @Test
    void seamUnitCostDerivesFromStandardMinutesAndHourlyWage() {
        // 缝边标准 5 分钟：时薪 15 → 5 × 0.25 = 1.2500；时薪 24 → 5 × 0.4 = 2.0000；无种类 → 0
        plain(ProductPricing.seamUnitCost(5, new BigDecimal("15")), "1.2500");
        plain(ProductPricing.seamUnitCost(5, new BigDecimal("24")), "2.0000");
        plain(ProductPricing.seamUnitCost(null, new BigDecimal("15")), "0.0000");
    }

    @Test
    void highPrecisionUnitPricesRoundHalfUpAtMoneyNode() {
        // 100g 无损耗：100×0.0012345=0.12345 → scale4 HALF_UP 0.1235（HALF_EVEN 会得 0.1234）
        var result = input().weight(100)
                .glueUnitPrice("0.0012345").colorpasteUnitPrice("0.00034567").salePrice("1")
                .compute();

        assertThat(result.glueGrams()).isEqualTo(100);
        plain(result.glueCost(), "0.1235");
        plain(result.colorpasteCost(), "0.0346");
        plain(result.materialCost(), "0.1581");
        plain(result.totalCost(), "0.1581");
        plain(result.referencePrice(), "0.2259");
        plain(result.estimatedProfit(), "0.8419");
        plain(result.estimatedMarginRate(), "0.841900");
    }

    @Test
    void integerMinutesDrivePackagingFee() {
        // 包装档位 9 分钟（整数）+ 商品包装提成 0.3：9×(15÷60)=2.25+0.3=2.55→2.5500
        var result = input().tier("9", "0.3").box("0.5").salePrice("5").compute();

        plain(result.productLaborFee(), "0.0000");
        plain(result.packagingLaborFee(), "2.5500");
        plain(result.materialCost(), "0.0000");
        plain(result.laborCost(), "3.0500");
        plain(result.totalCost(), "3.0500");
        plain(result.referencePrice(), "4.3571");
        plain(result.estimatedProfit(), "1.9500");
        plain(result.estimatedMarginRate(), "0.390000");
    }

    @Test
    void otherCostsSumIntoTotalCost() {
        // 0.3+0.2+0.4+0.1=1.0000；1/0.7=1.4285714…→1.4286；2/3=0.6666666…→0.666667
        var result = input().transport("0.3").sundries("0.2").rent("0.4").mold("0.1").salePrice("3").compute();

        plain(result.otherCost(), "1.0000");
        plain(result.laborCost(), "0.0000");
        plain(result.totalCost(), "1.0000");
        plain(result.referencePrice(), "1.4286");
        plain(result.estimatedProfit(), "2.0000");
        plain(result.estimatedMarginRate(), "0.666667");
    }

    @Test
    void zeroFeesAndZeroStdMinutesAvoidDivisionByZero() {
        var result = input().salePrice("10").compute();

        assertThat(result.glueGrams()).isZero();
        assertThat(result.qty8h()).isZero();
        assertThat(result.qty6h()).isZero();
        plain(result.productLaborFee(), "0.0000");
        plain(result.packagingLaborFee(), "0.0000");
        plain(result.materialCost(), "0.0000");
        plain(result.totalCost(), "0.0000");
        plain(result.referencePrice(), "0.0000");
        plain(result.estimatedProfit(), "10.0000");
        plain(result.estimatedMarginRate(), "1.000000");
    }

    @Test
    void zeroSalePriceYieldsZeroMarginAndNegativeProfit() {
        var result = input().transport("1").salePrice("0").compute();

        plain(result.totalCost(), "1.0000");
        plain(result.estimatedProfit(), "-1.0000");
        plain(result.estimatedMarginRate(), "0.000000");
    }

    @Test
    void targetMarginRateDrivesReferencePrice() {
        // 成本 10：利润率 30% → 10 ÷ 0.7 = 14.2857；50% → 10 ÷ 0.5 = 20.0000；0% → 10 ÷ 1 = 10.0000
        plain(input().star(0).targetMarginRate("0.3").salePrice("0")
                .weight(0).compute().referencePrice(), "0.0000");
        var atThirty = ProductPricing.referencePrice(new BigDecimal("10.0000"), new BigDecimal("0.3"));
        var atFifty = ProductPricing.referencePrice(new BigDecimal("10.0000"), new BigDecimal("0.5"));
        var atZero = ProductPricing.referencePrice(new BigDecimal("10.0000"), BigDecimal.ZERO);
        var atInvalid = ProductPricing.referencePrice(new BigDecimal("10.0000"), new BigDecimal("1.5"));

        plain(atThirty, "14.2857");
        plain(atFifty, "20.0000");
        plain(atZero, "10.0000");
        plain(atInvalid, "10.0000");
    }

    @Test
    void seamBudgetAddsSeamUnitCostWithoutTouchingProductCost() {
        // 缝边剪袋变体（FP-PROD-20/21）：不缝边剪袋总成本 1.0000 + 种类成本单价 1.2500 = 2.2500；
        // 2.25/0.7 = 3.2142857… → 3.2143；缝边价格 2.0000 只作收费展示，不进成本
        var seam = ProductPricing.seamBudget(new BigDecimal("1.0000"),
                new BigDecimal("1.2500"), new BigDecimal("2.0000"), new BigDecimal("0.3"));

        plain(seam.seamUnitCost(), "1.2500");
        plain(seam.seamFee(), "2.0000");
        plain(seam.totalCost(), "2.2500");
        plain(seam.referencePrice(), "3.2143");
    }

    @Test
    void seamBudgetTreatsMissingSeamTypeCostAndFeeAsZero() {
        // 缝边种类成本单价允许为 0；商品未填缝边价格按 0，变体总成本回到不缝边剪袋口径
        var seam = ProductPricing.seamBudget(new BigDecimal("16.2200"), null, null, new BigDecimal("0.3"));

        plain(seam.seamUnitCost(), "0.0000");
        plain(seam.seamFee(), "0.0000");
        plain(seam.totalCost(), "16.2200");
        plain(seam.referencePrice(), "23.1714");
    }

    /** 金额（scale4）与比例（scale6）均以字符串逐位比对，不接受浮点近似。 */
    private static void plain(BigDecimal actual, String expected) {
        assertThat(actual.toPlainString()).isEqualTo(expected);
    }

    private static Builder input() {
        return new Builder();
    }

    /** 测试用输入构造器：字段缺省为 0，档位缺省为“无包装”。 */
    private static final class Builder {

        private int weightG;
        private BigDecimal lossRate = BigDecimal.ZERO;
        private int stdMinutes;
        private Integer tierStdMinutes;
        private BigDecimal packagingCommission;
        private BigDecimal hourlyWage = new BigDecimal("15");
        private BigDecimal workdayHours = new BigDecimal("8");
        private BigDecimal makingEffectiveHourRate = new BigDecimal("0.75");
        private BigDecimal targetMarginRate = new BigDecimal("0.3");
        private BigDecimal glueUnitPrice = BigDecimal.ZERO;
        private BigDecimal colorpasteUnitPrice = BigDecimal.ZERO;
        private BigDecimal boxLaborFee = BigDecimal.ZERO;
        private BigDecimal transportPackingFee = BigDecimal.ZERO;
        private BigDecimal dailySundriesFee = BigDecimal.ZERO;
        private BigDecimal rentUtilitiesFee = BigDecimal.ZERO;
        private BigDecimal moldAmortFee = BigDecimal.ZERO;
        private BigDecimal salePrice = BigDecimal.ZERO;

        Builder weight(int value) {
            this.weightG = value;
            return this;
        }

        Builder lossRate(String value) {
            this.lossRate = new BigDecimal(value);
            return this;
        }

        Builder star(int value) {
            this.stdMinutes = value;
            return this;
        }

        Builder tier(String stdMinutes, String commission) {
            this.tierStdMinutes = Integer.valueOf(stdMinutes.trim());
            this.packagingCommission = new BigDecimal(commission);
            return this;
        }

        Builder hourlyWage(String value) {
            this.hourlyWage = new BigDecimal(value);
            return this;
        }

        Builder workdayHours(String value) {
            this.workdayHours = new BigDecimal(value);
            return this;
        }

        Builder targetMarginRate(String value) {
            this.targetMarginRate = new BigDecimal(value);
            return this;
        }

        Builder effectiveHourRate(String value) {
            this.makingEffectiveHourRate = new BigDecimal(value);
            return this;
        }

        Builder glueUnitPrice(String value) {
            this.glueUnitPrice = new BigDecimal(value);
            return this;
        }

        Builder colorpasteUnitPrice(String value) {
            this.colorpasteUnitPrice = new BigDecimal(value);
            return this;
        }

        Builder box(String value) {
            this.boxLaborFee = new BigDecimal(value);
            return this;
        }

        Builder transport(String value) {
            this.transportPackingFee = new BigDecimal(value);
            return this;
        }

        Builder sundries(String value) {
            this.dailySundriesFee = new BigDecimal(value);
            return this;
        }

        Builder rent(String value) {
            this.rentUtilitiesFee = new BigDecimal(value);
            return this;
        }

        Builder mold(String value) {
            this.moldAmortFee = new BigDecimal(value);
            return this;
        }

        Builder salePrice(String value) {
            this.salePrice = new BigDecimal(value);
            return this;
        }

        ProductPricing.Result compute() {
            return ProductPricing.compute(new ProductPricing.Inputs(weightG, lossRate, stdMinutes,
                    tierStdMinutes, packagingCommission, hourlyWage, workdayHours, makingEffectiveHourRate,
                    targetMarginRate,
                    glueUnitPrice, colorpasteUnitPrice, boxLaborFee,
                    transportPackingFee, dailySundriesFee, rentUtilitiesFee, moldAmortFee, salePrice));
        }
    }
}

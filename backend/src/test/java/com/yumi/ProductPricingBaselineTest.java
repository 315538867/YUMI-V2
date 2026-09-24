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
    void fractionalMinutesDrivePackagingFee() {
        // 包装档位 8.5 分钟 + 商品包装提成 0.3：8.5×0.25=2.125+0.3=2.425→2.4250
        var result = input().tier("8.5", "0.3").box("0.5").salePrice("5").compute();

        plain(result.productLaborFee(), "0.0000");
        plain(result.packagingLaborFee(), "2.4250");
        plain(result.materialCost(), "0.0000");
        plain(result.laborCost(), "2.9250");
        plain(result.totalCost(), "2.9250");
        plain(result.referencePrice(), "4.1786");
        plain(result.estimatedProfit(), "2.0750");
        plain(result.estimatedMarginRate(), "0.415000");
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
    void seamBudgetAddsSeamUnitCostWithoutTouchingProductCost() {
        // 缝边剪袋变体（FP-PROD-20/21）：不缝边剪袋总成本 1.0000 + 种类成本单价 1.2500 = 2.2500；
        // 2.25/0.7 = 3.2142857… → 3.2143；缝边价格 2.0000 只作收费展示，不进成本
        var seam = ProductPricing.seamBudget(new BigDecimal("1.0000"),
                new BigDecimal("1.2500"), new BigDecimal("2.0000"));

        plain(seam.seamUnitCost(), "1.2500");
        plain(seam.seamFee(), "2.0000");
        plain(seam.totalCost(), "2.2500");
        plain(seam.referencePrice(), "3.2143");
    }

    @Test
    void seamBudgetTreatsMissingSeamTypeCostAndFeeAsZero() {
        // 缝边种类成本单价允许为 0；商品未填缝边价格按 0，变体总成本回到不缝边剪袋口径
        var seam = ProductPricing.seamBudget(new BigDecimal("16.2200"), null, null);

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
        private BigDecimal tierStdMinutes;
        private BigDecimal packagingCommission;
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
            this.tierStdMinutes = new BigDecimal(stdMinutes);
            this.packagingCommission = new BigDecimal(commission);
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
                    tierStdMinutes, packagingCommission, glueUnitPrice, colorpasteUnitPrice, boxLaborFee,
                    transportPackingFee, dailySundriesFee, rentUtilitiesFee, moldAmortFee, salePrice));
        }
    }
}

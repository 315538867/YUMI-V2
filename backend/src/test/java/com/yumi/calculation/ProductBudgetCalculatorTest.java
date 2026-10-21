package com.yumi.calculation;

import static org.assertj.core.api.Assertions.assertThat;

import com.yumi.calculation.api.BudgetLine;
import com.yumi.calculation.api.CapacityCalculator;
import com.yumi.calculation.api.CostBearer;
import com.yumi.calculation.api.CostNature;
import com.yumi.calculation.api.DailyCapacityInput;
import com.yumi.calculation.api.DailyCapacityResult;
import com.yumi.calculation.api.ProductBudgetCalculator;
import com.yumi.calculation.api.ProductBudgetInput;
import com.yumi.calculation.api.ProductBudgetResult;
import com.yumi.calculation.api.WorkProcedure;
import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.LossRate;
import com.yumi.calculation.api.Ratio;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.platform.api.value.WeightGram;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 商品单件预算、供料承担、标准产能与零售价利润率（CA-01）。
 *
 * <p>规则来源：`docs/一期/07-计算中心模块.md` §4.1、§4.2、§4.8、§5—§7。
 * 关键点：
 * <ul>
 *   <li>胶水标准用量 = 标准克重 × (1 + 损耗率)，色浆直接使用该用量，不重复乘损耗；</li>
 *   <li>每条明细有且只有一个承担方；客户供料不进入工作室承担成本；</li>
 *   <li>目标工序决定并入哪些工序，额外单件费用只加一次；</li>
 *   <li>售价为零时利润金额仍可计算，利润率为空；预计利润允许为负；</li>
 *   <li>结果返回公式代码、规则版本、舍入版本与输入指纹。</li>
 * </ul>
 */
class ProductBudgetCalculatorTest {

    private final ProductBudgetCalculator calculator = ProductBudgetCalculator.create();

    @Nested
    @DisplayName("胶水与色浆损耗")
    class LossContract {

        @Test
        @DisplayName("105 克示例：色浆使用含损耗用量，不再乘一次损耗")
        void colorPasteUsesLossAppliedOnce() {
            ProductBudgetInput input = input()
                    .glueStandardWeight(WeightGram.ofGrams("100"))
                    .glueLossRate(LossRate.of("0.05"))
                    .glueUnitPricePerGram(cost("0.10"))
                    .colorPasteUnitPricePerGram(cost("0.20"))
                    .build();

            ProductBudgetResult result = calculator.calculate(input);

            assertThat(result.glueUsage().value()).isEqualByComparingTo("105.0000");

            BudgetLine glue = line(result, "GLUE");
            BudgetLine colorPaste = line(result, "COLOR_PASTE");
            assertThat(glue.fullAmount().value()).isEqualByComparingTo("10.5000");
            assertThat(colorPaste.fullAmount().value())
                    .as("色浆 = 105 克 × 0.20，不得再乘 1.05")
                    .isEqualByComparingTo("21.0000");
        }

        @Test
        @DisplayName("客户供料保留全资源价值但不进入工作室承担成本")
        void customerSuppliedMaterialIsSeparated() {
            ProductBudgetInput input = input()
                    .glueStandardWeight(WeightGram.ofGrams("100"))
                    .glueUnitPricePerGram(cost("0.10"))
                    .colorPasteUnitPricePerGram(cost("0.20"))
                    .glueBearer(CostBearer.CUSTOMER)
                    .build();

            ProductBudgetResult result = calculator.calculate(input);

            assertThat(result.customerSuppliedValue().value()).isEqualByComparingTo("10.5000");
            assertThat(result.studioCost().value())
                    .isEqualByComparingTo(result.fullCost().value().subtract(new BigDecimal("10.5000")));
            assertThat(result.fullCost().value())
                    .isEqualByComparingTo(result.studioCost().value().add(result.customerSuppliedValue().value()));
            assertThat(line(result, "GLUE").bearer()).isEqualTo(CostBearer.CUSTOMER);
        }

        @Test
        @DisplayName("守恒关系成立：明细合计等于汇总，承担方拆分后回到全资源成本")
        void totalsAreConserved() {
            ProductBudgetResult result = calculator.calculate(input().build());

            BigDecimal lineSum = result.lines().stream()
                    .map(line -> line.fullAmount().value())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            assertThat(lineSum).isEqualByComparingTo(result.fullCost().value());
            assertThat(result.studioCost().value().add(result.customerSuppliedValue().value()))
                    .isEqualByComparingTo(result.fullCost().value());
        }
    }

    @Nested
    @DisplayName("目标工序范围")
    class TargetProcedureContract {

        @Test
        @DisplayName("MAKING 只含制作工序，额外单件费用只加一次")
        void makingOnly() {
            ProductBudgetResult result = calculator.calculate(
                    input().targetProcedure(WorkProcedure.MAKING).build());

            assertThat(codes(result)).contains("GLUE", "COLOR_PASTE", "MAKING_LABOR", "MAKING_OTHER_MATERIAL")
                    .doesNotContain("BAGGING_LABOR", "SEAM_LABOR");
            assertThat(codes(result)).filteredOn(code -> code.startsWith("EXTRA_")).hasSize(6);
        }

        @Test
        @DisplayName("BAGGING 含制作与捏毛装袋，不含缝边剪袋")
        void baggingIncludesMaking() {
            ProductBudgetResult result = calculator.calculate(
                    input().targetProcedure(WorkProcedure.BAGGING).build());

            assertThat(codes(result)).contains("MAKING_LABOR", "BAGGING_LABOR", "BAGGING_BAG")
                    .doesNotContain("SEAM_LABOR", "SEAM_COMPOSITE_MATERIAL");
        }

        @Test
        @DisplayName("SEAM_CUTTING 含全部三道工序")
        void seamCuttingIncludesAll() {
            ProductBudgetResult result = calculator.calculate(
                    input().targetProcedure(WorkProcedure.SEAM_CUTTING).build());

            assertThat(codes(result)).contains(
                    "MAKING_LABOR", "BAGGING_LABOR", "SEAM_LABOR", "SEAM_COMPOSITE_MATERIAL");
        }

        @Test
        @DisplayName("人工明细固定由工作室承担")
        void laborIsAlwaysStudioBorne() {
            ProductBudgetResult result = calculator.calculate(input().build());
            assertThat(result.lines())
                    .filteredOn(line -> line.nature() == CostNature.LABOR)
                    .allMatch(line -> line.bearer() == CostBearer.STUDIO);
        }
    }

    @Nested
    @DisplayName("售价、利润与利润率")
    class ProfitContract {

        @Test
        @DisplayName("售价为零时利润仍可计算，利润率为空")
        void zeroPriceYieldsEmptyMargin() {
            ProductBudgetResult result = calculator.calculate(
                    input().sellingPrice(SettlementMoney.of(new BigDecimal("0"), CurrencyCode.CNY)).build());

            assertThat(result.estimatedProfit().value().signum()).isLessThan(0);
            assertThat(result.profitMargin()).isEmpty();
        }

        @Test
        @DisplayName("负利润允许存在，不抛校验错误")
        void negativeProfitIsAllowed() {
            ProductBudgetResult result = calculator.calculate(
                    input().sellingPrice(SettlementMoney.of(new BigDecimal("0.01"), CurrencyCode.CNY)).build());

            assertThat(result.estimatedProfit().value().signum()).isLessThan(0);
            assertThat(result.profitMargin()).isPresent();
            assertThat(result.profitMargin().orElseThrow().value().signum()).isLessThan(0);
        }

        @Test
        @DisplayName("利润 = 售价 − 工作室承担成本；利润率 = 利润 ÷ 售价")
        void profitFormula() {
            ProductBudgetResult result = calculator.calculate(
                    input().sellingPrice(SettlementMoney.of(new BigDecimal("100"), CurrencyCode.CNY)).build());

            assertThat(result.estimatedProfit().value()).isEqualByComparingTo(
                    new BigDecimal("100.00").subtract(result.studioCost().value())
                            .setScale(4, java.math.RoundingMode.HALF_UP));

            Optional<Ratio> margin = result.profitMargin();
            assertThat(margin).isPresent();
            assertThat(margin.orElseThrow().value()).isEqualByComparingTo(
                    result.estimatedProfit().value()
                            .divide(new BigDecimal("100"), 8, java.math.RoundingMode.HALF_UP));
        }
    }

    @Nested
    @DisplayName("标准单日产能")
    class CapacityContract {

        @Test
        @DisplayName("单日最大产能 = 模具数量 × 每日批次，不乘效率或人数")
        void capacityIsMoldTimesBatches() {
            DailyCapacityResult result = CapacityCalculator.calculate(
                    new DailyCapacityInput(3, 8));

            assertThat(result.maxDailyUnits()).isEqualTo(24);
            assertThat(result.trace().formulaCode()).isEqualTo("DAILY_CAPACITY");
            assertThat(result.trace().inputFingerprint()).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("模具数量或批次为零时产能为零；负数被拒绝")
        void capacityBounds() {
            assertThat(CapacityCalculator.calculate(new DailyCapacityInput(0, 8)).maxDailyUnits()).isZero();
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> CapacityCalculator.calculate(new DailyCapacityInput(-1, 8)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("模具数量");
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> CapacityCalculator.calculate(new DailyCapacityInput(2, 0)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("批次");
        }
    }

    @Nested
    @DisplayName("追溯标识与确定性")
    class TraceContract {

        @Test
        @DisplayName("相同输入返回相同结果与相同指纹；不同输入指纹不同")
        void deterministicAndFingerprinted() {
            ProductBudgetInput input = input().build();

            ProductBudgetResult first = calculator.calculate(input);
            ProductBudgetResult second = calculator.calculate(input);
            ProductBudgetResult changed = calculator.calculate(
                    input().glueUnitPricePerGram(cost("0.11")).build());

            assertThat(second.trace().inputFingerprint()).isEqualTo(first.trace().inputFingerprint());
            assertThat(changed.trace().inputFingerprint()).isNotEqualTo(first.trace().inputFingerprint());
            assertThat(first.trace().formulaCode()).isEqualTo("PRODUCT_BUDGET");
            assertThat(first.trace().ruleVersion()).isNotBlank();
            assertThat(first.trace().roundingVersion()).isEqualTo("HALF_UP-4");
        }

        @Test
        @DisplayName("输入缺失不补默认值，明确报错")
        void missingInputRejected() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> input().makingLabor(null).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("制作提成");
        }
    }

    private static List<String> codes(ProductBudgetResult result) {
        return result.lines().stream().map(BudgetLine::lineCode).toList();
    }

    private static BudgetLine line(ProductBudgetResult result, String code) {
        return result.lines().stream()
                .filter(line -> line.lineCode().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少预算明细：" + code));
    }

    private static CostAmount cost(String value) {
        return CostAmount.of(new BigDecimal(value), CurrencyCode.CNY);
    }

    private static InputBuilder input() {
        return new InputBuilder();
    }

    /** 测试用输入构造器，默认值代表一个完整的标准方案。 */
    private static final class InputBuilder {

        private WeightGram glueStandardWeight = WeightGram.ofGrams("100");
        private LossRate glueLossRate = LossRate.of("0.05");
        private CostAmount glueUnitPricePerGram = CostAmount.of(new BigDecimal("0.10"), CurrencyCode.CNY);
        private CostAmount colorPasteUnitPricePerGram = CostAmount.of(new BigDecimal("0.20"), CurrencyCode.CNY);
        private CostBearer glueBearer = CostBearer.STUDIO;
        private CostBearer colorPasteBearer = CostBearer.STUDIO;
        private WorkProcedure targetProcedure = WorkProcedure.SEAM_CUTTING;
        private CostAmount makingLabor = CostAmount.of(new BigDecimal("1.0000"), CurrencyCode.CNY);
        private CostAmount makingOtherMaterial = CostAmount.of(new BigDecimal("0.2000"), CurrencyCode.CNY);
        private CostAmount baggingLabor = CostAmount.of(new BigDecimal("0.5000"), CurrencyCode.CNY);
        private CostAmount baggingBag = CostAmount.of(new BigDecimal("0.1000"), CurrencyCode.CNY);
        private CostAmount baggingDecoration = CostAmount.of(new BigDecimal("0.0500"), CurrencyCode.CNY);
        private CostAmount baggingOther = CostAmount.of(new BigDecimal("0.0200"), CurrencyCode.CNY);
        private CostAmount seamLabor = CostAmount.of(new BigDecimal("0.6000"), CurrencyCode.CNY);
        private CostAmount seamCompositeMaterial = CostAmount.of(new BigDecimal("0.0300"), CurrencyCode.CNY);
        private CostAmount seamOther = CostAmount.of(new BigDecimal("0.0100"), CurrencyCode.CNY);
        private CostAmount extraOtherTaskLabor = CostAmount.of(new BigDecimal("0.0100"), CurrencyCode.CNY);
        private CostAmount extraTransportPackaging = CostAmount.of(new BigDecimal("0.0200"), CurrencyCode.CNY);
        private CostAmount extraDailyMisc = CostAmount.of(new BigDecimal("0.0300"), CurrencyCode.CNY);
        private CostAmount extraRentUtilities = CostAmount.of(new BigDecimal("0.0400"), CurrencyCode.CNY);
        private CostAmount extraMoldAmortization = CostAmount.of(new BigDecimal("0.0500"), CurrencyCode.CNY);
        private CostAmount extraOtherFee = CostAmount.of(new BigDecimal("0.0600"), CurrencyCode.CNY);
        private SettlementMoney sellingPrice = SettlementMoney.of(new BigDecimal("50"), CurrencyCode.CNY);

        InputBuilder glueStandardWeight(WeightGram value) {
            this.glueStandardWeight = value;
            return this;
        }

        InputBuilder glueLossRate(LossRate value) {
            this.glueLossRate = value;
            return this;
        }

        InputBuilder glueUnitPricePerGram(CostAmount value) {
            this.glueUnitPricePerGram = value;
            return this;
        }

        InputBuilder colorPasteUnitPricePerGram(CostAmount value) {
            this.colorPasteUnitPricePerGram = value;
            return this;
        }

        InputBuilder glueBearer(CostBearer value) {
            this.glueBearer = value;
            return this;
        }

        InputBuilder targetProcedure(WorkProcedure value) {
            this.targetProcedure = value;
            return this;
        }

        InputBuilder makingLabor(CostAmount value) {
            this.makingLabor = value;
            return this;
        }

        InputBuilder sellingPrice(SettlementMoney value) {
            this.sellingPrice = value;
            return this;
        }

        ProductBudgetInput build() {
            return new ProductBudgetInput(
                    glueStandardWeight, glueLossRate, glueUnitPricePerGram, colorPasteUnitPricePerGram,
                    glueBearer, colorPasteBearer, targetProcedure,
                    makingLabor, makingOtherMaterial, baggingLabor, baggingBag, baggingDecoration, baggingOther,
                    seamLabor, seamCompositeMaterial, seamOther,
                    extraOtherTaskLabor, extraTransportPackaging, extraDailyMisc,
                    extraRentUtilities, extraMoldAmortization, extraOtherFee,
                    sellingPrice);
        }
    }
}

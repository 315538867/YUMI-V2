package com.yumi.calculation;

import com.yumi.calculation.api.BudgetLine;
import com.yumi.calculation.api.CapacityCalculator;
import com.yumi.calculation.api.CostBearer;
import com.yumi.calculation.api.CostNature;
import com.yumi.calculation.api.DailyCapacityInput;
import com.yumi.calculation.api.DailyCapacityResult;
import com.yumi.calculation.api.FormulaFingerprints;
import com.yumi.calculation.api.FormulaTrace;
import com.yumi.calculation.api.FormulaVersions;
import com.yumi.calculation.api.ProductBudgetCalculator;
import com.yumi.calculation.api.ProductBudgetInput;
import com.yumi.calculation.api.ProductBudgetResult;
import com.yumi.calculation.api.Ratio;
import com.yumi.calculation.api.WorkProcedure;
import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.SignedCostAmount;
import com.yumi.platform.api.value.CurrencyCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 商品单件预算实现。
 *
 * <p>严格按基线 07 §4.2 的拆解：先算含损耗胶水用量，再算胶水与色浆成本；工序成本按目标工序
 * 并入；商品额外单件费用作为独立明细只加一次。所有中间值使用未舍入精度，明细在输出边界按
 * {@code HALF_UP} 舍入到 4 位，汇总只累加已舍入明细。
 */
public final class ProductBudgetCalculatorImpl implements ProductBudgetCalculator {

    @Override
    public ProductBudgetResult calculate(ProductBudgetInput input) {
        if (input == null) {
            throw new IllegalArgumentException("预算输入不能为空");
        }

        // 含损耗的胶水标准用量：损耗只应用一次
        BigDecimal glueUsage = input.glueStandardWeight().value().multiply(input.glueLossRate().multiplier());
        CostAmount glueCost = cost(glueUsage.multiply(input.glueUnitPricePerGram().value()));
        CostAmount colorPasteCost = cost(glueUsage.multiply(input.colorPasteUnitPricePerGram().value()));

        List<BudgetLine> lines = new ArrayList<>();
        lines.add(line("GLUE", "胶水", CostNature.NON_LABOR_MATERIAL, input.glueBearer(), WorkProcedure.MAKING, glueCost));
        lines.add(line("COLOR_PASTE", "色浆", CostNature.NON_LABOR_MATERIAL, input.colorPasteBearer(),
                WorkProcedure.MAKING, colorPasteCost));

        // 制作工序始终在范围内
        lines.add(line("MAKING_LABOR", "制作提成", CostNature.LABOR, CostBearer.STUDIO,
                WorkProcedure.MAKING, input.makingLabor()));
        lines.add(line("MAKING_OTHER_MATERIAL", "制作其他材料", CostNature.NON_LABOR_MATERIAL,
                CostBearer.STUDIO, WorkProcedure.MAKING, input.makingOtherMaterial()));

        if (input.targetProcedure() == WorkProcedure.BAGGING
                || input.targetProcedure() == WorkProcedure.SEAM_CUTTING) {
            lines.add(line("BAGGING_LABOR", "捏毛装袋人工", CostNature.LABOR, CostBearer.STUDIO,
                    WorkProcedure.BAGGING, input.baggingLabor()));
            lines.add(line("BAGGING_BAG", "单件袋子成本", CostNature.NON_LABOR_MATERIAL, CostBearer.STUDIO,
                    WorkProcedure.BAGGING, input.baggingBag()));
            lines.add(line("BAGGING_DECORATION", "单件装饰品", CostNature.NON_LABOR_MATERIAL, CostBearer.STUDIO,
                    WorkProcedure.BAGGING, input.baggingDecoration()));
            lines.add(line("BAGGING_OTHER", "捏毛装袋其他费用", CostNature.NON_LABOR_MATERIAL, CostBearer.STUDIO,
                    WorkProcedure.BAGGING, input.baggingOther()));
        }

        if (input.targetProcedure() == WorkProcedure.SEAM_CUTTING) {
            lines.add(line("SEAM_LABOR", "缝边剪袋人工", CostNature.LABOR, CostBearer.STUDIO,
                    WorkProcedure.SEAM_CUTTING, input.seamLabor()));
            lines.add(line("SEAM_COMPOSITE_MATERIAL", "综合材料", CostNature.NON_LABOR_MATERIAL,
                    CostBearer.STUDIO, WorkProcedure.SEAM_CUTTING, input.seamCompositeMaterial()));
            lines.add(line("SEAM_OTHER", "缝边剪袋其他费用", CostNature.NON_LABOR_MATERIAL, CostBearer.STUDIO,
                    WorkProcedure.SEAM_CUTTING, input.seamOther()));
        }

        // 商品额外单件费用：不属于三道工序，作为独立明细只加入一次
        lines.add(line("EXTRA_OTHER_TASK_LABOR", "其他任务人工费", CostNature.LABOR, CostBearer.STUDIO,
                null, input.extraOtherTaskLabor()));
        lines.add(line("EXTRA_TRANSPORT_PACKAGING", "运输包装费", CostNature.EXTRA_PIECE_FEE, CostBearer.STUDIO,
                null, input.extraTransportPackaging()));
        lines.add(line("EXTRA_DAILY_MISC", "日常杂费", CostNature.EXTRA_PIECE_FEE, CostBearer.STUDIO,
                null, input.extraDailyMisc()));
        lines.add(line("EXTRA_RENT_UTILITIES", "房租水电费", CostNature.EXTRA_PIECE_FEE, CostBearer.STUDIO,
                null, input.extraRentUtilities()));
        lines.add(line("EXTRA_MOLD_AMORTIZATION", "模具单件摊销费", CostNature.EXTRA_PIECE_FEE, CostBearer.STUDIO,
                null, input.extraMoldAmortization()));
        lines.add(line("EXTRA_OTHER_FEE", "其他费用", CostNature.EXTRA_PIECE_FEE, CostBearer.STUDIO,
                null, input.extraOtherFee()));

        CostAmount fullCost = sum(lines.stream().map(BudgetLine::fullAmount).toList());
        CostAmount studioCost = sum(lines.stream()
                .filter(line -> line.bearer() == CostBearer.STUDIO)
                .map(BudgetLine::fullAmount)
                .toList());
        CostAmount customerValue = sum(lines.stream()
                .filter(line -> line.bearer() == CostBearer.CUSTOMER)
                .map(BudgetLine::fullAmount)
                .toList());

        if (studioCost.plus(customerValue).value().compareTo(fullCost.value()) != 0) {
            throw new IllegalStateException(
                    "预算守恒失败：工作室承担 + 客户供料 必须等于全资源成本");
        }

        BigDecimal profit = input.sellingPrice().value()
                .subtract(studioCost.value())
                .setScale(4, RoundingMode.HALF_UP);
        // 预计利润允许为负：使用有符号成本差额类型，不能用无符号成本金额
        SignedCostAmount estimatedProfit = SignedCostAmount.of(profit, CurrencyCode.CNY);

        Optional<Ratio> margin = input.sellingPrice().value().signum() == 0
                ? Optional.empty()
                : Optional.of(Ratio.of(profit.divide(
                        input.sellingPrice().value(), Ratio.SCALE, RoundingMode.HALF_UP)));

        FormulaTrace trace = new FormulaTrace(
                FormulaVersions.PRODUCT_BUDGET,
                FormulaVersions.RULE_VERSION,
                FormulaVersions.ROUNDING_COST_4,
                FormulaFingerprints.of(
                        "glueWeight", input.glueStandardWeight().value(),
                        "glueLossRate", input.glueLossRate().value(),
                        "gluePrice", input.glueUnitPricePerGram().value(),
                        "colorPastePrice", input.colorPasteUnitPricePerGram().value(),
                        "glueBearer", input.glueBearer(),
                        "colorPasteBearer", input.colorPasteBearer(),
                        "targetProcedure", input.targetProcedure(),
                        "makingLabor", input.makingLabor().value(),
                        "makingOtherMaterial", input.makingOtherMaterial().value(),
                        "baggingLabor", input.baggingLabor().value(),
                        "baggingBag", input.baggingBag().value(),
                        "baggingDecoration", input.baggingDecoration().value(),
                        "baggingOther", input.baggingOther().value(),
                        "seamLabor", input.seamLabor().value(),
                        "seamCompositeMaterial", input.seamCompositeMaterial().value(),
                        "seamOther", input.seamOther().value(),
                        "extraOtherTaskLabor", input.extraOtherTaskLabor().value(),
                        "extraTransportPackaging", input.extraTransportPackaging().value(),
                        "extraDailyMisc", input.extraDailyMisc().value(),
                        "extraRentUtilities", input.extraRentUtilities().value(),
                        "extraMoldAmortization", input.extraMoldAmortization().value(),
                        "extraOtherFee", input.extraOtherFee().value(),
                        "sellingPrice", input.sellingPrice().value()));

        return new ProductBudgetResult(
                com.yumi.platform.api.value.WeightGram.ofGrams(glueUsage),
                List.copyOf(lines),
                fullCost,
                studioCost,
                customerValue,
                estimatedProfit,
                margin,
                trace);
    }

    private static BudgetLine line(
            String code,
            String label,
            CostNature nature,
            CostBearer bearer,
            WorkProcedure procedure,
            CostAmount amount) {
        return new BudgetLine(code, label, nature, bearer, procedure, CostAmount.of(amount.value(), amount.currency()));
    }

    private static CostAmount cost(BigDecimal raw) {
        return CostAmount.of(raw, CurrencyCode.CNY);
    }

    private static CostAmount sum(List<CostAmount> amounts) {
        BigDecimal total = amounts.stream()
                .map(CostAmount::value)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return CostAmount.of(total, CurrencyCode.CNY);
    }
}

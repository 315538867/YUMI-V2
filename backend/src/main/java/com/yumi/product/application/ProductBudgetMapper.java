package com.yumi.product.application;

import com.yumi.calculation.api.CapacityCalculator;
import com.yumi.calculation.api.CostBearer;
import com.yumi.calculation.api.DailyCapacityInput;
import com.yumi.calculation.api.DailyCapacityResult;
import com.yumi.calculation.api.ProductBudgetCalculator;
import com.yumi.calculation.api.ProductBudgetInput;
import com.yumi.calculation.api.ProductBudgetResult;
import com.yumi.calculation.api.WorkProcedure;
import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.LossRate;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.platform.api.value.WeightGram;
import com.yumi.product.api.MaterialSource;
import com.yumi.product.api.SchemeView;
import com.yumi.product.application.port.BudgetRepository;
import com.yumi.product.application.port.ProductRecord;
import com.yumi.product.application.port.SchemeRecord;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 商品与方案的预算映射。
 *
 * <p>把商品聚合（胶水损耗、单价、提成、工时、额外费用）与方案字段（目标工序、售价、供料方）
 * 组装成计算中心输入，并把计算结果映射为可持久化的预算记录。商品与方案只保存输入与结果，
 * 不复制计算中心公式（基线 07 §4.9、09 §6）。
 */
public final class ProductBudgetMapper {

    private final ProductBudgetCalculator budgetCalculator;

    public ProductBudgetMapper(ProductBudgetCalculator budgetCalculator) {
        this.budgetCalculator = budgetCalculator;
    }

    /** 默认装配：使用计算中心的纯函数实现。 */
    public static ProductBudgetMapper defaults() {
        return new ProductBudgetMapper(ProductBudgetCalculator.create());
    }

    /** 计算商品单日产能；结果由商品模块保存，生产模块只读取。 */
    public DailyCapacityResult capacity(int mouldCount, int mouldDailyBatches) {
        return CapacityCalculator.calculate(new DailyCapacityInput(mouldCount, mouldDailyBatches));
    }

    /**
     * 计算方案预算。
     *
     * <p>人工、工时与额外单件费用取自商品；目标工序、售价与供料方取自方案。
     */
    public ProductBudgetResult calculateBudget(ProductRecord product, SchemeRecord scheme) {
        ProductRecord.ProcessCosts costs = product.processCosts();
        ProductBudgetInput input = new ProductBudgetInput(
                WeightGram.ofGrams(product.standardWeightGrams()),
                LossRate.of(product.glueLossRate()),
                cost(product.glueUnitPrice()),
                cost(product.colorPasteUnitPrice()),
                bearer(scheme.glueSource()),
                bearer(scheme.colorPasteSource()),
                WorkProcedure.valueOf(scheme.targetProcess()),
                cost(product.makingCommission()),
                cost(costs.makingOtherMaterialCost()),
                cost(costs.baggingLaborCost()),
                cost(costs.baggingBagUnitCost()),
                cost(costs.baggingDecorationUnitPrice()),
                cost(costs.baggingOtherCost()),
                cost(costs.seamLaborCost()),
                cost(costs.seamCombinedMaterialCost()),
                cost(costs.seamOtherCost()),
                cost(costs.extraOtherTaskLaborCost()),
                cost(costs.extraTransportPackagingCost()),
                cost(costs.extraDailyMiscCost()),
                cost(costs.extraRentUtilitiesCost()),
                cost(costs.extraMouldAmortizationCost()),
                cost(costs.extraOtherCost()),
                SettlementMoney.of(scheme.salePrice(), CurrencyCode.CNY));
        return budgetCalculator.calculate(input);
    }

    /** 把预算结果映射为持久化记录。 */
    public BudgetRepository.BudgetRecord toRecord(
            String budgetBusinessId,
            long productContentVersion,
            long schemeContentVersion,
            ProductBudgetResult result,
            String schemeBusinessId) {
        List<BudgetRepository.BudgetLineRecord> lines = new ArrayList<>();
        int lineNo = 1;
        for (var line : result.lines()) {
            lines.add(new BudgetRepository.BudgetLineRecord(
                    lineNo++,
                    line.procedure() == null ? null : line.procedure().name(),
                    line.lineCode(),
                    line.nature().name(),
                    line.bearer().name(),
                    BigDecimal.ONE,
                    line.fullAmount().value(),
                    line.fullAmount().value(),
                    line.bearer() == CostBearer.STUDIO ? line.fullAmount().value() : BigDecimal.ZERO));
        }
        // 预期单件收入 = 售价；方案售价在 2 位精度上保存
        BigDecimal expectedRevenue = result.estimatedProfit().value()
                .add(result.studioCost().value())
                .setScale(2, RoundingMode.HALF_UP);
        return new BudgetRepository.BudgetRecord(
                budgetBusinessId,
                schemeBusinessId,
                productContentVersion,
                schemeContentVersion,
                result.trace().ruleVersion(),
                result.trace().roundingVersion(),
                result.trace().inputFingerprint(),
                result.fullCost().value(),
                result.studioCost().value(),
                result.customerSuppliedValue().value(),
                expectedRevenue,
                result.estimatedProfit().value(),
                result.profitMargin().map(ratio -> ratio.value()).orElse(null),
                lines);
    }

    /** 供方案视图展示的预算映射。 */
    public static SchemeView.SchemeBudgetView toView(BudgetRepository.BudgetRecord record) {
        List<SchemeView.BudgetLineView> lines = record.lines().stream()
                .map(line -> new SchemeView.BudgetLineView(
                        line.componentCode(),
                        line.processCode(),
                        line.costNature(),
                        line.bearerType(),
                        line.quantity(),
                        cost(line.unitPrice()),
                        cost(line.fullResourceCost()),
                        cost(line.studioBorneCost())))
                .toList();
        return new SchemeView.SchemeBudgetView(
                cost(record.fullResourceUnitCost()),
                cost(record.studioBorneUnitCost()),
                cost(record.customerSuppliedUnitValue()),
                com.yumi.platform.api.value.SignedCostAmount.of(record.expectedUnitProfit()),
                Optional.ofNullable(record.expectedProfitRate()),
                record.formulaRuleVersion(),
                record.roundingVersion(),
                record.inputFingerprint(),
                lines);
    }

    private static CostBearer bearer(String source) {
        return MaterialSource.valueOf(source) == MaterialSource.CUSTOMER
                ? CostBearer.CUSTOMER
                : CostBearer.STUDIO;
    }

    private static CostAmount cost(BigDecimal value) {
        return value == null
                ? CostAmount.of(BigDecimal.ZERO, CurrencyCode.CNY)
                : CostAmount.of(value, CurrencyCode.CNY);
    }
}

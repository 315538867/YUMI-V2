package com.yumi.calculation;

import java.util.List;

/**
 * 公式目录（设计第 9 节）：记录已实现公式的稳定标识、分类、输入与单位、表达式、舍入节点、
 * 结果含义、固定示例、代码位置与测试编号。
 * 编号一经使用不复用：FP-PROD-09/10 为原商品缝边公式，2026-09-24 随缝边移出商品分类
 * （缝边改为订单明细行的定制服务），其编号保留不再使用，成本与收费公式随订单实现登记到 order 分类。
 * 目录随实现与测试一同更新，**不是运行配置**：不落库、不提供编辑或发布接口，只供只读查询。
 * 未建设的业务（工资、财务等）不在此登记空条目。
 */
public final class FormulaCatalog {

    public static final String CATEGORY_PRODUCT = "商品";

    public record Entry(
            String identifier,
            String category,
            String name,
            String inputs,
            String expression,
            String rounding,
            String resultMeaning,
            String example,
            String codeLocation,
            String testId) {
    }

    private static final List<Entry> ENTRIES = List.of(
            product("01", "损耗率换算", "百分比文本 %", "percent ÷ 100", "scale6 HALF_UP",
                    "内部损耗比例", "33.3333 → 0.333333",
                    "calculation/DecimalPolicy.percentToRatio",
                    "ProductPricingBaselineTest#lossRateConversionUsesScale6HalfUp"),
            product("02", "胶水克重", "重量 g、损耗比例", "weightG × (1 + lossRate)", "取整 HALF_UP",
                    "单件耗用克重", "105 × 1.5 = 157.5 → 158",
                    "calculation/product/ProductPricing.compute",
                    "ProductPricingBaselineTest#glueGramsRoundsHalfUpAtPointFiveBoundary"),
            product("03", "胶水成本", "克重、胶水单价", "glueGrams × glueUnitPrice", "scale4 HALF_UP",
                    "单件胶水成本", "324 × 0.0100 = 3.2400",
                    "calculation/product/ProductPricing.compute",
                    "ProductPricingBaselineTest#highPrecisionUnitPricesRoundHalfUpAtMoneyNode"),
            product("04", "色浆成本", "克重、色浆单价", "glueGrams × colorpasteUnitPrice", "scale4 HALF_UP",
                    "单件色浆成本", "324 × 0.0200 = 6.4800",
                    "calculation/product/ProductPricing.compute",
                    "ProductPricingBaselineTest#highPrecisionUnitPricesRoundHalfUpAtMoneyNode"),
            product("05", "8 小时标准数量", "星级标准时长 min", "floor(480 ÷ stdMinutes)", "向下取整",
                    "8 小时满速产量", "480 ÷ 7 = 68.57 → 68",
                    "calculation/product/ProductPricing.compute",
                    "ProductPricingBaselineTest#nonDivisibleStandardMinutesFloorBeforeLaborFee"),
            product("06", "6 小时标准数量", "星级标准时长 min", "floor(360 ÷ stdMinutes)", "向下取整",
                    "6 小时满速产量", "360 ÷ 7 = 51.43 → 51",
                    "calculation/product/ProductPricing.compute",
                    "ProductPricingBaselineTest#nonDivisibleStandardMinutesFloorBeforeLaborFee"),
            product("07", "星级单件人工费", "6 小时标准数量", "qty6 = 0 → 0，否则 120 ÷ qty6", "scale4 HALF_UP",
                    "单件星级人工", "120 ÷ 51 = 2.3529",
                    "calculation/product/ProductPricing.compute",
                    "ProductPricingBaselineTest#nonDivisibleStandardMinutesFloorBeforeLaborFee"),
            product("08", "包装人工费", "档位标准分钟、商品包装提成", "档位分钟 × 0.25 + 商品包装提成；无档位 → 0",
                    "scale4 HALF_UP", "单件包装人工", "8.5 × 0.25 + 0.3 = 2.4250",
                    "calculation/product/ProductPricing.compute",
                    "ProductPricingBaselineTest#fractionalMinutesDrivePackagingFee"),
            product("11", "材料成本", "胶水成本、色浆成本", "glueCost + colorpasteCost", "scale4 HALF_UP",
                    "材料分项", "3.2400 + 6.4800 = 9.7200",
                    "calculation/product/ProductPricing.compute",
                    "ProductPricingBaselineTest#highPrecisionUnitPricesRoundHalfUpAtMoneyNode"),
            product("12", "人工成本", "星级人工、包装人工、装箱人工",
                    "productLaborFee + packagingLaborFee + boxLaborFee", "scale4 HALF_UP",
                    "人工分项", "5.0000 + 0.0000 + 0.5000 = 5.5000",
                    "calculation/product/ProductPricing.compute",
                    "ProductPricingTest#pinnedExampleMatchesEveryFormula"),
            product("13", "其他成本", "运输包装、日常杂费、房租水电、模具摊销", "四项相加", "scale4 HALF_UP",
                    "其他分项", "0.3 + 0.2 + 0.4 + 0.1 = 1.0000",
                    "calculation/product/ProductPricing.compute",
                    "ProductPricingBaselineTest#otherCostsSumIntoTotalCost"),
            product("14", "单件总成本", "三项成本", "materialCost + laborCost + otherCost", "scale4 HALF_UP",
                    "基础单件成本", "9.7200 + 5.5000 + 1.0000 = 16.2200",
                    "calculation/product/ProductPricing.compute",
                    "ProductPricingTest#pinnedExampleMatchesEveryFormula"),
            product("15", "参考售价", "单件总成本", "totalCost ÷ 0.7", "scale4 HALF_UP",
                    "按目标毛利率反推售价", "16.2200 ÷ 0.7 = 23.1714",
                    "calculation/product/ProductPricing.compute",
                    "ProductPricingTest#pinnedExampleMatchesEveryFormula"),
            product("16", "预计利润", "成交价、单件总成本", "salePrice − totalCost", "scale4 HALF_UP",
                    "读时派生，允许为负", "25.0000 − 16.2200 = 8.7800",
                    "calculation/product/ProductPricing.estimatedProfit",
                    "ProductPricingBaselineTest#zeroSalePriceYieldsZeroMarginAndNegativeProfit"),
            product("17", "预计利润率", "成交价、单件总成本",
                    "salePrice = 0 → 0，否则 (salePrice − totalCost) ÷ salePrice", "scale6 HALF_UP",
                    "读时派生比例", "(25.0000 − 16.2200) ÷ 25.0000 = 0.351200",
                    "calculation/product/ProductPricing.estimatedMarginRate",
                    "ProductPricingTest#pinnedExampleMatchesEveryFormula"),
            product("18", "损耗率回显百分比", "内部损耗比例", "lossRate × 100", "scale6 HALF_UP",
                    "接口回显百分比文本", "0.200000 × 100 = 20.000000",
                    "calculation/DecimalPolicy.ratioToPercent",
                    "ProductPricingTest#pinnedExampleMatchesEveryFormula"),
            product("19", "金额精度工具", "任意 BigDecimal", "原值转 scale4", "scale4 HALF_UP",
                    "金额统一口径", "0.12345 → 0.1235",
                    "calculation/DecimalPolicy.money",
                    "ProductPricingBaselineTest#highPrecisionUnitPricesRoundHalfUpAtMoneyNode"));

    private FormulaCatalog() {
    }

    /** 已实现的公式条目；按分类分组由调用方完成。 */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    private static Entry product(String sequence, String name, String inputs, String expression,
                                String rounding, String resultMeaning, String example,
                                String codeLocation, String testId) {
        return new Entry("FP-PROD-" + sequence, CATEGORY_PRODUCT, name, inputs, expression, rounding,
                resultMeaning, example, codeLocation, testId);
    }
}

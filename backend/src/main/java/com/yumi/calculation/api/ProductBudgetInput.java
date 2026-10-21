package com.yumi.calculation.api;

import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.LossRate;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.platform.api.value.WeightGram;
import java.util.Objects;

/**
 * 商品单件预算输入。
 *
 * <p>所有金额均为<b>单件</b>口径：商品模块传入的额外费用已经是每件金额，计算中心不再按订单数量
 * 或产量分摊。胶水与色浆的承担方分别声明，不改变用量或全资源价值。
 *
 * <p>依赖来源：`docs/一期/07-计算中心模块.md` §4.2。
 */
public record ProductBudgetInput(
        /** 单件标准克重（克/件）。 */
        WeightGram glueStandardWeight,
        /** 胶水损耗率，`[0,1)`，只应用一次。 */
        LossRate glueLossRate,
        /** 胶水每克单价。 */
        CostAmount glueUnitPricePerGram,
        /** 色浆每克单价。 */
        CostAmount colorPasteUnitPricePerGram,
        /** 胶水承担方。 */
        CostBearer glueBearer,
        /** 色浆承担方。 */
        CostBearer colorPasteBearer,
        /** 商品目标工序；决定哪些工序进入预算范围。 */
        WorkProcedure targetProcedure,
        /** 制作提成（人工）。 */
        CostAmount makingLabor,
        /** 制作工序其他材料（非人工）。 */
        CostAmount makingOtherMaterial,
        /** 捏毛装袋人工成本。 */
        CostAmount baggingLabor,
        /** 单件袋子成本。 */
        CostAmount baggingBag,
        /** 单件装饰品价格。 */
        CostAmount baggingDecoration,
        /** 捏毛装袋其他费用。 */
        CostAmount baggingOther,
        /** 缝边剪袋人工成本。 */
        CostAmount seamLabor,
        /** 单件综合材料成本。 */
        CostAmount seamCompositeMaterial,
        /** 缝边剪袋其他费用。 */
        CostAmount seamOther,
        /** 商品额外单件费用：其他任务人工费。 */
        CostAmount extraOtherTaskLabor,
        /** 商品额外单件费用：运输包装费。 */
        CostAmount extraTransportPackaging,
        /** 商品额外单件费用：日常杂费。 */
        CostAmount extraDailyMisc,
        /** 商品额外单件费用：房租水电费。 */
        CostAmount extraRentUtilities,
        /** 商品额外单件费用：模具单件摊销费。 */
        CostAmount extraMoldAmortization,
        /** 商品额外单件费用：其他费用。 */
        CostAmount extraOtherFee,
        /** 方案销售价；为零时利润率返回空。 */
        SettlementMoney sellingPrice) {

    public ProductBudgetInput {
        Objects.requireNonNull(glueStandardWeight, "单件标准克重不能为空");
        Objects.requireNonNull(glueLossRate, "胶水损耗率不能为空");
        Objects.requireNonNull(glueUnitPricePerGram, "胶水单价不能为空");
        Objects.requireNonNull(colorPasteUnitPricePerGram, "色浆单价不能为空");
        Objects.requireNonNull(glueBearer, "胶水承担方不能为空");
        Objects.requireNonNull(colorPasteBearer, "色浆承担方不能为空");
        Objects.requireNonNull(targetProcedure, "商品目标工序不能为空");
        Objects.requireNonNull(sellingPrice, "方案销售价不能为空");
        requirePresent("制作提成", makingLabor);
        requirePresent("制作其他材料", makingOtherMaterial);
        requirePresent("捏毛装袋人工", baggingLabor);
        requirePresent("单件袋子成本", baggingBag);
        requirePresent("单件装饰品", baggingDecoration);
        requirePresent("捏毛装袋其他费用", baggingOther);
        requirePresent("缝边剪袋人工", seamLabor);
        requirePresent("综合材料", seamCompositeMaterial);
        requirePresent("缝边剪袋其他费用", seamOther);
        requirePresent("其他任务人工费", extraOtherTaskLabor);
        requirePresent("运输包装费", extraTransportPackaging);
        requirePresent("日常杂费", extraDailyMisc);
        requirePresent("房租水电费", extraRentUtilities);
        requirePresent("模具单件摊销", extraMoldAmortization);
        requirePresent("其他费用", extraOtherFee);
    }

    private static void requirePresent(String label, CostAmount amount) {
        if (amount == null) {
            throw new IllegalArgumentException(label + " 不能为空（缺值请传 0，不得省略）");
        }
    }
}

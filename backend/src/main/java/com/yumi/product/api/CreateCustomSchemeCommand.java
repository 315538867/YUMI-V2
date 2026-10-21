package com.yumi.product.api;

import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.SettlementMoney;

/**
 * 创建自定义方案命令。
 *
 * <p>只允许覆盖明确的非人工字段与供料方；<b>不提供</b>人工、工时、提成与额外单件费用字段，
 * 这些由商品同步。目标工序可选短流程。
 *
 * @param expectedProductContentVersion 提交时商品的当前内容版本，用于避免基于过时继承值提交
 */
public record CreateCustomSchemeCommand(
        String productBusinessId,
        String name,
        SchemeView.ProcessCode targetProcess,
        SettlementMoney salePrice,
        MaterialSource glueSource,
        MaterialSource colorPasteSource,
        CostAmount makingOtherMaterialCost,
        CostAmount baggingBagUnitCost,
        MaterialSource baggingBagSource,
        CostAmount baggingDecorationUnitPrice,
        MaterialSource baggingDecorationSource,
        CostAmount baggingOtherCost,
        CostAmount seamCombinedMaterialCost,
        CostAmount seamOtherCost,
        long expectedProductContentVersion) {
}

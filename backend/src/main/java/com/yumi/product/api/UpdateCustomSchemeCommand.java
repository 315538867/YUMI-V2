package com.yumi.product.api;

import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.SettlementMoney;

/**
 * 修改自定义方案命令。
 *
 * <p>与创建同样只开放非人工字段；人工、工时、提成与额外单件费用继承商品，不接受覆盖。
 */
public record UpdateCustomSchemeCommand(
        String schemeBusinessId,
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
        long expectedProductContentVersion,
        long expectedSchemeRowVersion) {
}

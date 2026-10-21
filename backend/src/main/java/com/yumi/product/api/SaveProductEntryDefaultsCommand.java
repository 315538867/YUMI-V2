package com.yumi.product.api;

import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.LossRate;

/**
 * 保存录入默认值命令。
 *
 * @param expectedRowVersion 当前配置行版本；首次保存为 0
 */
public record SaveProductEntryDefaultsCommand(
        LossRate glueLossRate,
        CostAmount glueUnitPrice,
        CostAmount colorPasteUnitPrice,
        long expectedRowVersion) {
}

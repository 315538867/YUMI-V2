package com.yumi.finance.costledger.api;

import com.yumi.platform.api.value.CostAmount;

/** 成本事实引用。 */
public record CostEntryRef(String costEntryBusinessId, CostAmount amount, boolean allocated) {
}

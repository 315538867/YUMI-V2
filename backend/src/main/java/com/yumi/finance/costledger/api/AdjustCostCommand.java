package com.yumi.finance.costledger.api;

import com.yumi.platform.api.value.CostAmount;

/** 精确冲正并替代一条历史成本。 */
public record AdjustCostCommand(
        String idempotencyKey, String costEntryBusinessId, CostAmount newAmount,
        String reasonText) {

    public AdjustCostCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("成本更正必须携带幂等键");
        }
        if (costEntryBusinessId == null || costEntryBusinessId.isBlank()) {
            throw new IllegalArgumentException("成本更正必须指定原成本事实");
        }
        if (newAmount == null) {
            throw new IllegalArgumentException("成本更正必须给出替代金额");
        }
        if (reasonText == null || reasonText.isBlank()) {
            throw new IllegalArgumentException("成本更正必须填写原因");
        }
    }
}

package com.yumi.finance.costledger.api;

/** 归集售后正式成本来源。 */
public record AccrueAfterSalesCostsCommand(String idempotencyKey) {

    public AccrueAfterSalesCostsCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("售后成本归集必须携带幂等键");
        }
    }
}

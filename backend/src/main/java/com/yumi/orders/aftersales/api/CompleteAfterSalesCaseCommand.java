package com.yumi.orders.aftersales.api;

/** 结案命令。 */
public record CompleteAfterSalesCaseCommand(String idempotencyKey, String caseBusinessId) {

    public CompleteAfterSalesCaseCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("正式结案命令必须携带幂等键");
        }
        if (caseBusinessId == null || caseBusinessId.isBlank()) {
            throw new IllegalArgumentException("结案必须指定售后案例");
        }
    }
}

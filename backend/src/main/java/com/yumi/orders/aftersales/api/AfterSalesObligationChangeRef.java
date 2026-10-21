package com.yumi.orders.aftersales.api;

/** 修复失败转路线结果。 */
public record AfterSalesObligationChangeRef(
        String sourceObligationBusinessId,
        String successorObligationBusinessId,
        AfterSalesObligationChangeRoute route,
        int quantity) {
}

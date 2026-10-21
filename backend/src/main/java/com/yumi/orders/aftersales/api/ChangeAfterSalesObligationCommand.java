package com.yumi.orders.aftersales.api;

import com.yumi.platform.api.value.SettlementMoney;

/**
 * 修复失败转路线命令：只转换生产返回的当前缺口。
 *
 * @param quantity 本次转换缺口数量
 * @param amount 退款/赔付金额；其他路线为空
 */
public record ChangeAfterSalesObligationCommand(
        String idempotencyKey,
        String sourceObligationBusinessId,
        int quantity,
        AfterSalesObligationChangeRoute route,
        SettlementMoney amount,
        String note) {

    public ChangeAfterSalesObligationCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("义务变更必须携带幂等键");
        }
        if (sourceObligationBusinessId == null || sourceObligationBusinessId.isBlank()) {
            throw new IllegalArgumentException("义务变更必须指定原售后义务");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("转路线数量必须大于零");
        }
        if (route == null) {
            throw new IllegalArgumentException("义务变更必须选择路线");
        }
        if ((route == AfterSalesObligationChangeRoute.REFUND
                || route == AfterSalesObligationChangeRoute.COMPENSATION)
                && amount == null) {
            throw new IllegalArgumentException("退款或赔付路线必须给出金额");
        }
        if (route != AfterSalesObligationChangeRoute.REFUND
                && route != AfterSalesObligationChangeRoute.COMPENSATION
                && amount != null) {
            throw new IllegalArgumentException("非金额路线不能填写金额");
        }
    }
}

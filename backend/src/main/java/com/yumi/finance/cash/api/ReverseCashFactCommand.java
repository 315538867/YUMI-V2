package com.yumi.finance.cash.api;

import java.time.LocalDate;

/** 错误现金整笔冲销：追加反向现金事实并反向原业务关联。 */
public record ReverseCashFactCommand(
        String idempotencyKey, String cashFactBusinessId, LocalDate businessDate,
        String reasonText) {

    public ReverseCashFactCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("现金冲销必须携带幂等键");
        }
        if (cashFactBusinessId == null || cashFactBusinessId.isBlank()) {
            throw new IllegalArgumentException("现金冲销必须指定原现金事实");
        }
        if (businessDate == null || reasonText == null || reasonText.isBlank()) {
            throw new IllegalArgumentException("现金冲销必须填写业务日期和原因");
        }
    }
}

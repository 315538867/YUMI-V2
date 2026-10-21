package com.yumi.finance.cash.api;

import com.yumi.platform.api.value.SettlementMoney;
import java.time.LocalDate;

/** 现金事实引用。 */
public record CashFactRef(
        String cashFactBusinessId,
        String cashNo,
        String direction,
        String cashType,
        SettlementMoney amount,
        LocalDate businessDate) {
}

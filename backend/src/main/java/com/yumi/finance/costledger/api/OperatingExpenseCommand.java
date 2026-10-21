package com.yumi.finance.costledger.api;

import com.yumi.platform.api.value.CostAmount;
import java.time.LocalDate;

/** 经营费用入账：由 cash 在同一事务调用。 */
public record OperatingExpenseCommand(
        String expenseBusinessId,
        String cashFactBusinessId,
        CostAmount amount,
        LocalDate businessDate,
        String categoryCode) {

    public OperatingExpenseCommand {
        if (expenseBusinessId == null || expenseBusinessId.isBlank()
                || cashFactBusinessId == null || cashFactBusinessId.isBlank()) {
            throw new IllegalArgumentException("经营费用必须关联现金事实");
        }
        if (amount == null || amount.value().signum() <= 0) {
            throw new IllegalArgumentException("经营费用金额必须大于零");
        }
        if (businessDate == null || categoryCode == null || categoryCode.isBlank()) {
            throw new IllegalArgumentException("经营费用必须填写业务日期和分类");
        }
    }
}

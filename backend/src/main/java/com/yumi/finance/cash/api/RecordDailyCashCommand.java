package com.yumi.finance.cash.api;

import com.yumi.platform.api.value.SettlementMoney;
import java.time.LocalDate;
import java.util.List;

/**
 * 日常收支：支出可显式选择计经营费用；收入不记经营费用。
 */
public record RecordDailyCashCommand(
        String idempotencyKey,
        String direction,
        String categoryCode,
        LocalDate businessDate,
        SettlementMoney amount,
        String description,
        boolean countAsExpense,
        List<String> voucherFileRefs) {

    public RecordDailyCashCommand(
            String idempotencyKey, String direction, String categoryCode, LocalDate businessDate,
            SettlementMoney amount, String description, boolean countAsExpense) {
        this(idempotencyKey, direction, categoryCode, businessDate, amount, description,
                countAsExpense, List.of());
    }

    public RecordDailyCashCommand {
        voucherFileRefs = List.copyOf(voucherFileRefs == null ? List.of() : voucherFileRefs);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("日常收支必须携带幂等键");
        }
        if (!"IN".equals(direction) && !"OUT".equals(direction)) {
            throw new IllegalArgumentException("日常收支方向必须为 IN 或 OUT");
        }
        if (categoryCode == null || categoryCode.isBlank()) {
            throw new IllegalArgumentException("日常收支必须填写分类");
        }
        if (businessDate == null || amount == null || amount.value().signum() <= 0) {
            throw new IllegalArgumentException("日常收支必须填写业务日期和金额");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("日常收支必须填写用途");
        }
        if (countAsExpense && !"OUT".equals(direction)) {
            throw new IllegalArgumentException("收入不能计经营费用");
        }
    }
}

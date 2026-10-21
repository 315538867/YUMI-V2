package com.yumi.finance.costledger.api;

import java.time.LocalDate;

/** 重试失败来源：按来源类型重跑归集并标记失败已恢复。 */
public record RetryCostFailureCommand(
        String idempotencyKey, String failureBusinessId, LocalDate startDate, LocalDate endDate) {

    public RetryCostFailureCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("失败重试必须携带幂等键");
        }
        if (failureBusinessId == null || failureBusinessId.isBlank()) {
            throw new IllegalArgumentException("失败重试必须指定失败记录");
        }
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("失败重试日期范围不合法");
        }
    }
}

package com.yumi.finance.costledger.api;

/** 成本归集运行结果。 */
public record CostAccrualRunRef(String runBusinessId, int accruedCount, int failedCount) {
}

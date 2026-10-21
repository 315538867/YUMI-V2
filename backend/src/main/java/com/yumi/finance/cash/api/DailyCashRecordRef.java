package com.yumi.finance.cash.api;

/** 日常收支结果。 */
public record DailyCashRecordRef(
        String dailyRecordBusinessId, CashFactRef cashFact, String costEntryBusinessId) {
}

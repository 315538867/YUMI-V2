package com.yumi.orders.aftersales.api;

import java.time.Instant;

/** 售后结案结果。 */
public record AfterSalesCompletionRef(String caseBusinessId, Instant completedAt) {
}

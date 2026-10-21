package com.yumi.customer.api;

import java.time.Instant;

/** 客户资料变化事实，只读展示；历史订单引用不因停用或改名改变。 */
public record CustomerChangeView(
        String factBusinessId,
        long versionBefore,
        long versionAfter,
        String operationType,
        String changedFieldsJson,
        String reasonText,
        Instant occurredAt,
        String operatorId) {
}

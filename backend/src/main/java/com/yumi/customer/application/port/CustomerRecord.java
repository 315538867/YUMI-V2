package com.yumi.customer.application.port;

import com.yumi.customer.api.CustomerStatus;
import java.time.Instant;

/** 客户当前记录（模块内部类型）。 */
public record CustomerRecord(
        long id,
        String customerBusinessId,
        String customerNo,
        String name,
        String contactName,
        String phone,
        String defaultAddress,
        CustomerStatus status,
        long contentVersion,
        long rowVersion,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy) {
}

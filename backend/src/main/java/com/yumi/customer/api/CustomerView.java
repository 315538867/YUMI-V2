package com.yumi.customer.api;

/** 客户当前资料视图。 */
public record CustomerView(
        String customerBusinessId,
        String customerNo,
        String name,
        String contactName,
        String phone,
        String defaultAddress,
        CustomerStatus status,
        long contentVersion,
        long rowVersion,
        java.time.Instant updatedAt) {
}

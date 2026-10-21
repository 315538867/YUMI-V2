package com.yumi.customer.api;

/** 客户引用。 */
public record CustomerRef(
        String customerBusinessId, String customerNo, CustomerStatus status, long contentVersion,
        long rowVersion) {
}

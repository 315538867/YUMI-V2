package com.yumi.product.api;

/** 方案引用。 */
public record SchemeRef(
        String schemeBusinessId, String productBusinessId, SchemeType schemeType,
        long contentVersion, long rowVersion) {
}

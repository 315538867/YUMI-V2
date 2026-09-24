package com.yumi.catalog.product;

/** 启停入参：reason 可选，写入变更日志。 */
public record ToggleProductRequest(String reason) {
}

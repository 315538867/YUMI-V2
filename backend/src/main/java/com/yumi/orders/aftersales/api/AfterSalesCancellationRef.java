package com.yumi.orders.aftersales.api;

/**
 * 取消义务结果；数量依据保存在义务变更事实的服务端 JSON 中。
 */
public record AfterSalesCancellationRef(String obligationBusinessId, boolean voided) {
}

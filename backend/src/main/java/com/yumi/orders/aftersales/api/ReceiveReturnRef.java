package com.yumi.orders.aftersales.api;

/** 退回接收结果。 */
public record ReceiveReturnRef(
        String receiptFactBusinessId,
        String receiptItemBusinessId,
        String productionReturnedSourceBusinessId,
        int quantity,
        int availablePoolQuantity) {
}

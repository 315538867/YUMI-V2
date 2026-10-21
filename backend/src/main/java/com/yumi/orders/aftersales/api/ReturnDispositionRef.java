package com.yumi.orders.aftersales.api;

/** 售后池处置结果。 */
public record ReturnDispositionRef(
        String dispositionFactBusinessId,
        String receiptItemBusinessId,
        String productionDispositionFactBusinessId,
        String productionTerminalExitBusinessId,
        String productionOfflineTransferFactBusinessId,
        int quantity) {
}

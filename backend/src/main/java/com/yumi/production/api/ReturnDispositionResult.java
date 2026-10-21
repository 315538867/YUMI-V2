package com.yumi.production.api;

/** 退回制品池处置结果。 */
public record ReturnDispositionResult(
        String dispositionFactBusinessId,
        String terminalExitBusinessId,
        String consumptionFactBusinessId,
        String transferFactBusinessId,
        String responsibilityChainBusinessId,
        int disposedQuantity,
        int availableQuantity) {
}

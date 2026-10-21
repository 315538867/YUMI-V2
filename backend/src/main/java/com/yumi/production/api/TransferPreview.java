package com.yumi.production.api;

/** 移交准备结果。 */
public record TransferPreview(
        String transferFactBusinessId,
        String terminalExitBusinessId,
        String productionSourceBusinessId,
        String physicalRootSourceBusinessId,
        String demandBusinessId,
        int quantity,
        int availableToTransfer) {
}

package com.yumi.production.api;

/** 退回制品池处置准备结果；只在当前事务内有效。 */
public record PreparedReturnDisposition(
        String dispositionFactBusinessId,
        String terminalExitBusinessId,
        String consumptionFactBusinessId,
        String transferFactBusinessId,
        String responsibilityChainBusinessId,
        String returnedSourceBusinessId,
        String physicalRootSourceBusinessId,
        ReturnDispositionType dispositionType,
        PhysicalDestination physicalDestination,
        int quantity) {
}

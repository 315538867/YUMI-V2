package com.yumi.production.api;

/** 退回重入准备结果；只在当前事务内有效。 */
public record PreparedReturnReentry(
        String reentryFactBusinessId,
        String returnedSourceBusinessId,
        String afterSalesReturnReceiptItemBusinessId,
        String physicalRootSourceBusinessId,
        String productSnapshotRef,
        String schemeSnapshotRef,
        String returnedStageCode,
        String priorDeliverySourceType,
        String priorProductionSourceBusinessId,
        String priorReturnedSourceBusinessId,
        String priorConsumptionFactBusinessId,
        int quantity) {
}

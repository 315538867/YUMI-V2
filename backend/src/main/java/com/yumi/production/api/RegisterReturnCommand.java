package com.yumi.production.api;

/**
 * 售后退回接收命令：引用最近一次交付来源与消费事实，将数量重入退回制品池。
 */
public record RegisterReturnCommand(
        String afterSalesReturnReceiptItemBusinessId,
        String priorDeliverySourceType,
        String priorProductionSourceBusinessId,
        String priorReturnedSourceBusinessId,
        String priorConsumptionFactBusinessId,
        int quantity) {

    public RegisterReturnCommand {
        if (afterSalesReturnReceiptItemBusinessId == null
                || afterSalesReturnReceiptItemBusinessId.isBlank()) {
            throw new IllegalArgumentException("退回接收必须指定接收明细");
        }
        if (!"PRODUCTION_SOURCE".equals(priorDeliverySourceType)
                && !"RETURNED_PRODUCT_POOL".equals(priorDeliverySourceType)) {
            throw new IllegalArgumentException("退回必须引用生产来源或退回制品池来源");
        }
        boolean production = priorProductionSourceBusinessId != null;
        boolean returned = priorReturnedSourceBusinessId != null;
        if (production == returned) {
            throw new IllegalArgumentException("退回来源必须且只能指定一个");
        }
        if (priorConsumptionFactBusinessId == null || priorConsumptionFactBusinessId.isBlank()) {
            throw new IllegalArgumentException("退回必须引用原交付消费事实");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("退回数量必须大于零");
        }
    }
}

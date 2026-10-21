package com.yumi.orders.api;

import com.yumi.product.api.MaterialSource;
import com.yumi.product.api.ProductSnapshotRef;
import com.yumi.product.api.SchemeView;
import com.yumi.platform.api.value.SettlementMoney;

/**
 * 订单项录入请求。
 *
 * @param productBusinessId 产品编号
 * @param schemeSnapshotRef 录入时采用的方案快照；只作模板
 * @param targetProcess 最终目标工序
 * @param quantity 交付数量
 * @param unitPrice 单件成交价
 * @param materialSources 完整供料约定；不允许用“默认”含糊表达
 * @param recipient 本项收货信息
 */
public record OrderItemRequest(
        String productBusinessId,
        ProductSnapshotRef schemeSnapshotRef,
        SchemeView.ProcessCode targetProcess,
        int quantity,
        SettlementMoney unitPrice,
        MaterialSourceAgreement materialSources,
        RecipientInfo recipient) {

    public OrderItemRequest {
        if (productBusinessId == null || productBusinessId.isBlank()) {
            throw new IllegalArgumentException("订单项必须选择商品");
        }
        if (schemeSnapshotRef == null) {
            throw new IllegalArgumentException("订单项必须引用方案快照");
        }
        if (targetProcess == null) {
            throw new IllegalArgumentException("订单项必须明确最终目标工序");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("订单项数量必须大于 0");
        }
        if (unitPrice == null) {
            throw new IllegalArgumentException("订单项必须填写成交单价");
        }
        if (materialSources == null) {
            throw new IllegalArgumentException("订单项必须完整给出供料约定");
        }
        if (recipient == null) {
            throw new IllegalArgumentException("订单项必须填写收货信息");
        }
    }

    /** 完整供料约定：四项缺一不可。 */
    public record MaterialSourceAgreement(
            MaterialSource glue,
            MaterialSource colorPaste,
            MaterialSource bag,
            MaterialSource decoration) {

        public MaterialSourceAgreement {
            if (glue == null || colorPaste == null || bag == null || decoration == null) {
                throw new IllegalArgumentException("供料约定必须完整给出四项材料来源");
            }
        }
    }
}

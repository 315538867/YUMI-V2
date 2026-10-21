package com.yumi.orders.api;

import com.yumi.product.api.MaterialSource;
import com.yumi.product.api.ProductSnapshotRef;
import com.yumi.product.api.SchemeView;
import com.yumi.platform.api.value.SettlementMoney;

/**
 * 单条变更动作。
 *
 * @param kind 变更分类
 * @param orderItemBusinessId 受影响的订单项
 * @param newQuantity 变更后的有效数量；数量与取消类必填
 * @param newUnitPrice 新单价；价格类必填
 * @param newRecipient 新收货信息
 * @param newSchemeSnapshotRef 新方案快照；生产定义变更必填
 * @param newTargetProcess 新目标工序；生产定义变更必填
 * @param newMaterialSources 新供料约定；生产定义变更必填
 * @param migratedQuantity 迁往继任项的数量；生产定义变更必填
 */
public record OrderChangeAction(
        OrderChangeKind kind,
        String orderItemBusinessId,
        Integer newQuantity,
        SettlementMoney newUnitPrice,
        RecipientInfo newRecipient,
        ProductSnapshotRef newSchemeSnapshotRef,
        SchemeView.ProcessCode newTargetProcess,
        OrderItemRequest.MaterialSourceAgreement newMaterialSources,
        Integer migratedQuantity) {

    public OrderChangeAction {
        if (kind == null) {
            throw new IllegalArgumentException("必须给出变更分类");
        }
        if (orderItemBusinessId == null || orderItemBusinessId.isBlank()) {
            throw new IllegalArgumentException("变更动作必须指向订单项");
        }
        switch (kind) {
            case PRICE_OR_RECIPIENT -> {
                if (newUnitPrice == null && newRecipient == null) {
                    throw new IllegalArgumentException("价格或收货人变更必须给出新值");
                }
            }
            case QUANTITY -> {
                if (newQuantity == null || newQuantity < 0) {
                    throw new IllegalArgumentException("数量变更必须给出非负的新数量");
                }
            }
            case PRODUCTION_DEFINITION -> {
                if (newSchemeSnapshotRef == null || newTargetProcess == null
                        || newMaterialSources == null || migratedQuantity == null
                        || migratedQuantity <= 0) {
                    throw new IllegalArgumentException("生产定义变更必须给出方案快照、目标工序、供料约定与迁移数量");
                }
            }
            case CANCELLATION -> {
                // 取消不需要额外字段
            }
            default -> throw new IllegalArgumentException("未知变更分类");
        }
    }
}

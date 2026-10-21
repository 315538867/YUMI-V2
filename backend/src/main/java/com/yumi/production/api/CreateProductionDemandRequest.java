package com.yumi.production.api;

import com.yumi.platform.api.value.ContentFingerprint;

/**
 * 建立生产需求请求（订单或售后在同一事务内调用）。
 *
 * @param sourceModule 来源模块
 * @param sourceType 来源类型（普通订单、售后补发、售后返工等）
 * @param sourceBusinessId 来源稳定业务标识
 * @param demandType 新制或修复
 * @param productBusinessId 产品编号
 * @param productSnapshotRef 商品快照引用
 * @param schemeBusinessId 方案编号
 * @param schemeSnapshotRef 方案快照引用
 * @param basis 冻结的生效生产依据
 * @param requiredQuantity 需求数量
 * @param basisReturnedSourceBusinessId 修复需求的退回来源；新制为空
 */
public record CreateProductionDemandRequest(
        String sourceModule,
        String sourceType,
        String sourceBusinessId,
        String orderBusinessId,
        String orderItemBusinessId,
        DemandType demandType,
        String productBusinessId,
        String productSnapshotRef,
        String schemeBusinessId,
        String schemeSnapshotRef,
        ProductionBasis basis,
        int requiredQuantity,
        String basisReturnedSourceBusinessId) {

    public CreateProductionDemandRequest {
        if (demandType == null) {
            throw new IllegalArgumentException("需求类型不能为空");
        }
        if (basis == null) {
            throw new IllegalArgumentException("生产依据不能为空");
        }
        if (requiredQuantity <= 0) {
            throw new IllegalArgumentException("需求数量必须大于 0");
        }
        if (demandType == DemandType.NEW_PRODUCT && basisReturnedSourceBusinessId != null) {
            throw new IllegalArgumentException("新制需求不能关联退回来源");
        }
        if (demandType == DemandType.RETURNED_PRODUCT_REPAIR && basisReturnedSourceBusinessId == null) {
            throw new IllegalArgumentException("修复需求必须关联唯一退回制品来源");
        }
    }
}

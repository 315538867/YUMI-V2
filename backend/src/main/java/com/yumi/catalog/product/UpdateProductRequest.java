package com.yumi.catalog.product;

import java.math.BigDecimal;

/**
 * 编辑商品入参（2026-09-24 修订）：除 version 必填外均可选，未传字段保持原值。
 * 胶水损耗率、日常杂费、房租水电由服务端取当前全局设置，客户端提交会被忽略；
 * refreshMaterialPrices=true 才按当前全局单价刷新胶水/色浆快照；
 * refreshGlobalReferences=true 时星级、包装档位与材料单价全部按当前全局重算；
 * 默认缝边剪袋类型未传＝保持原值，clearSeamType=true 才清空为“默认不缝边剪袋”（与 seamTypeId 互斥）；
 * reason 可选入变更日志。
 */
public record UpdateProductRequest(
        Long version,
        String name,
        String note,
        Long starLevelId,
        BigDecimal salePrice,
        Integer weightG,
        Long packagingTierId,
        BigDecimal packagingCommission,
        Long seamTypeId,
        Boolean clearSeamType,
        BigDecimal seamFee,
        BigDecimal boxLaborFee,
        BigDecimal transportPackingFee,
        BigDecimal moldAmortFee,
        Long imageFileId,
        Boolean refreshMaterialPrices,
        Boolean refreshGlobalReferences,
        String reason) {
}

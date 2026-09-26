package com.yumi.catalog.product;

import java.math.BigDecimal;

/**
 * 创建商品入参（2026-09-24 修订）：商品只提交自身属性与引用。
 * 胶水损耗率、日常杂费、房租水电由服务端取当前全局设置，客户端提交会被忽略；
 * 装箱人工费、运输包装费未传时取全局默认；包装提成未传取全局默认；
 * 默认缝边剪袋类型未传＝默认不缝边剪袋；缝边价格未传＝0。
 * 模具数量与每日批次数必填且必须为正整数（阶段五 5.1），最大日容量由服务端派生。
 */
public record CreateProductRequest(
        String name,
        String note,
        Long starLevelId,
        BigDecimal salePrice,
        Integer weightG,
        Long packagingTierId,
        BigDecimal packagingCommission,
        Long seamTypeId,
        BigDecimal seamFee,
        BigDecimal boxLaborFee,
        BigDecimal transportPackingFee,
        BigDecimal moldAmortFee,
        Long imageFileId,
        Integer moldQuantity,
        Integer dailyBatchLimit) {
}

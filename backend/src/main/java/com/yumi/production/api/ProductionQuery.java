package com.yumi.production.api;

import java.util.List;

/**
 * 生产公开查询（页面与报表只读此接口）。
 *
 * <p>不复制商品或人员快照载荷，只返回类型化引用与实际事实；读模型必须能核对两套数量账，
 * 且不得从计划数量推断完成数量。
 */
public interface ProductionQuery {

    /** 按订单项追溯生产需求、任务与实际事实。 */
    OrderProductionTrace traceOfOrderItem(String orderItemBusinessId);

    /** 按生产需求读取两套数量账。 */
    ProductionQuantityLedger ledgerOfDemand(String demandBusinessId);

    /** 某产品的需求列表（含数量账摘要）。 */
    List<ProductionProjectionRow> projectionsOfProduct(String productBusinessId, int limit);

    /** 某生产来源的转线下记录（报废转线下与业务移交分别标记）。 */
    java.util.List<OfflineTransferView> offlineTransfersOfSource(String productionSourceBusinessId);
}

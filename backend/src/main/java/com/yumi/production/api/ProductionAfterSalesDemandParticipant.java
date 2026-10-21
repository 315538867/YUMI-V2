package com.yumi.production.api;

/**
 * 售后修复需求与修复失败转路线参与接口（orders 在同一事务内调用）。
 *
 * <p>修复需求使用 {@code RETURNED_PRODUCT_REPAIR} 并沿用退回制品原始物理根；
 * 转路线只转换当前缺口，减少原修复需求，不建立任务或补发需求。
 */
public interface ProductionAfterSalesDemandParticipant {

    AfterSalesRepairDemandRef createRepairDemand(CreateAfterSalesRepairDemandCommand command);

    AfterSalesReplacementDemandRef createReplacementDemand(
            CreateAfterSalesReplacementDemandCommand command);

    RepairRoutePreview previewRouteChange(ChangeRepairRouteCommand command);

    /** 取消义务前的修复分支核对：待修复/在制数量未清零时不得取消。 */
    RepairDemandState describeRepairDemand(String repairDemandBusinessId);

    RepairRouteResult commitRouteChange(ChangeRepairRouteCommand command);
}

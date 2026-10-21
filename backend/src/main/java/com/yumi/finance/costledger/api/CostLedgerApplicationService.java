package com.yumi.finance.costledger.api;

/** 成本台账命令。 */
public interface CostLedgerApplicationService {

    CostAccrualRunRef accrueProductionMaterialCosts(AccrueProductionMaterialCostsCommand command);

    CostAccrualRunRef accrueDirectLaborCosts(AccrueDirectLaborCostsCommand command);

    CostAccrualRunRef accrueAfterSalesCosts(AccrueAfterSalesCostsCommand command);

    CostEntryRef adjust(AdjustCostCommand command);

    CostEntryRef allocate(AllocateCostCommand command);

    CostAccrualRunRef retryFailure(RetryCostFailureCommand command);
}

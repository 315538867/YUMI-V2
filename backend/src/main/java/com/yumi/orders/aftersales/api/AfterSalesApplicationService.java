package com.yumi.orders.aftersales.api;

/**
 * 售后案例命令。
 *
 * <p>规则来源：`docs/一期/12-订单模块.md` §13：只从已完成订单受理，
 * 分别维护客户配合、数量与金额义务，不覆盖原交易事实。
 */
public interface AfterSalesApplicationService {

    AfterSalesCaseRef open(OpenAfterSalesCaseCommand command);

    ReturnDispositionRef disposeReturnedProduct(DisposeReturnedProductCommand command);

    AfterSalesCompletionRef complete(CompleteAfterSalesCaseCommand command);

    AfterSalesAmountDecisionRef decideAmount(DecideAfterSalesAmountCommand command);
}

package com.yumi.orders.aftersales.api;

import java.util.List;

/** 售后费用只读查询。 */
public interface AfterSalesCostQuery {

    List<AfterSalesCostView> listByCase(String caseBusinessId);
}

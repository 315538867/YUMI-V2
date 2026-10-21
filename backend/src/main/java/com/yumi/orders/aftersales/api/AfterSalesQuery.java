package com.yumi.orders.aftersales.api;

import java.util.List;

/** 售后只读查询。 */
public interface AfterSalesQuery {

    AfterSalesCaseView get(String caseBusinessId);

    /** 案例列表：按案例号/订单号检索，可按状态过滤。 */
    List<AfterSalesCaseView> search(
            String keyword, AfterSalesCaseStatus status, int page, int size);
}

package com.yumi.customer.api;

import java.util.List;

/** 客户只读查询。 */
public interface CustomerQuery {

    CustomerView getCurrent(String customerBusinessId);

    List<CustomerView> search(String keyword, CustomerStatus status, int page, int size);

    /** 启用客户数量，用于列表页概览。 */
    long countActive();

    /** 客户资料变化历史，按发生时间升序。 */
    List<CustomerChangeView> history(String customerBusinessId);
}

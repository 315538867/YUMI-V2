package com.yumi.customer.api;

import java.util.List;

/** 客户只读查询。 */
public interface CustomerQuery {

    CustomerView getCurrent(String customerBusinessId);

    List<CustomerView> search(String keyword, CustomerStatus status, int page, int size);

    /** 客户资料变化历史，按发生时间升序。 */
    List<CustomerChangeView> history(String customerBusinessId);
}

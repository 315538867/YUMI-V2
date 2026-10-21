package com.yumi.customer.api;

/** 客户状态：停用只禁止新订单引用，不删除历史。 */
public enum CustomerStatus {
    ACTIVE,
    INACTIVE
}

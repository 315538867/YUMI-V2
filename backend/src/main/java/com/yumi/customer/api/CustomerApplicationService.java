package com.yumi.customer.api;

/**
 * 客户命令。
 *
 * <p>规则来源：`docs/一期/08-客户模块.md` §5。所有修改递增内容版本并记录变化事实；
 * 停用只禁止新订单引用，历史订单与快照不受影响。
 */
public interface CustomerApplicationService {

    CustomerRef create(CreateCustomerCommand command);

    CustomerRef update(UpdateCustomerCommand command);

    CustomerRef changeStatus(ChangeCustomerStatusCommand command);
}

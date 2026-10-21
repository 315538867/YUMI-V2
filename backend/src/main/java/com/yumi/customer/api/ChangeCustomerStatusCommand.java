package com.yumi.customer.api;

/** 启用或停用客户。 */
public record ChangeCustomerStatusCommand(
        String customerBusinessId, CustomerStatus targetStatus, long expectedRowVersion) {
}

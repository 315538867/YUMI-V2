package com.yumi.customer.api;

/**
 * 修改客户资料命令。
 *
 * @param expectedRowVersion 打开页面时的行版本；冲突时拒绝而不是静默覆盖
 */
public record UpdateCustomerCommand(
        String customerBusinessId,
        String name,
        String contactName,
        String phone,
        String defaultAddress,
        long expectedRowVersion) {
}

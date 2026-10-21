package com.yumi.customer.api;

/** 创建客户命令；不提供服务端草稿，一次提交完整资料。 */
public record CreateCustomerCommand(
        String name, String contactName, String phone, String defaultAddress) {
}

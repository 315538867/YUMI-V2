package com.yumi.platform.api.identity;

/**
 * 创建平台账号命令。
 *
 * <p>只创建登录主体；一期不建立员工与账号的绑定，因此本命令不含员工或人员字段。
 */
public record CreateAccountCommand(String loginName, String initialPassword, String displayName) {
}

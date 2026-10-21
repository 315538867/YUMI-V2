package com.yumi.platform.api.identity;

/** 登录命令。 */
public record LoginCommand(String loginName, String rawPassword) {
}

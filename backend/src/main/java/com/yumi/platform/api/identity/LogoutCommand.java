package com.yumi.platform.api.identity;

/** 退出命令：吊销当前会话。 */
public record LogoutCommand(String sessionToken) {
}

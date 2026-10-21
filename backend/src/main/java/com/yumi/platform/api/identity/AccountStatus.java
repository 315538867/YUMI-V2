package com.yumi.platform.api.identity;

/**
 * 账号状态（基线 06 §6）。
 *
 * <p>只有 {@link #ACTIVE} 可以登录和提交命令；{@link #LOCKED} 由连续登录失败产生，需要管理员解锁；
 * {@link #DISABLED} 由管理员停用，停用后既有会话立即失效。
 */
public enum AccountStatus {
    ACTIVE,
    LOCKED,
    DISABLED;

    public boolean canAuthenticate() {
        return this == ACTIVE;
    }
}

package com.yumi.platform.aftercommit.api;

/** 提交后任务状态（基线 06 §6）。 */
public enum AfterCommitTaskStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    /** 失败后等待退避重试。 */
    RETRY_WAIT,
    /** 达到重试上限，需人工恢复或放弃。 */
    DEAD
}

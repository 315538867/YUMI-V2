package com.yumi.platform.aftercommit.api;

/**
 * 提交后任务管理：授权重试与放弃。
 *
 * <p>需要 {@code platform.task.manage} 权限并写审计；重试沿用原任务身份与去重键，
 * 不重复产生业务事实；放弃只标记任务终止，不修改原业务。
 */
public interface AfterCommitTaskAdministrationService {

    void retry(String taskBusinessId);

    void abandon(String taskBusinessId, String reason);
}

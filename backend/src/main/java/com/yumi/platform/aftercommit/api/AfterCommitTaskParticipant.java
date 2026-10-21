package com.yumi.platform.aftercommit.api;

/**
 * 提交后任务参与接口：加入调用方事务。
 *
 * <p>任务记录随业务事务提交，只有事务成功后才允许 worker 领取；业务回滚时任务记录一并消失。
 * 后台任务失败不回滚已提交业务。
 */
public interface AfterCommitTaskParticipant {

    AfterCommitTaskRef enqueue(EnqueueAfterCommitTaskCommand command);
}

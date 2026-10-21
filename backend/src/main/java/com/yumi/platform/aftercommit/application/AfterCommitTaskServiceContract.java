package com.yumi.platform.aftercommit.application;

import com.yumi.platform.aftercommit.api.AfterCommitTaskParticipant;

/**
 * 提交后任务实现契约别名。
 *
 * <p>业务模块只依赖 {@code com.yumi.platform.aftercommit.api} 中的公开接口。
 */
public interface AfterCommitTaskServiceContract extends AfterCommitTaskParticipant {
}

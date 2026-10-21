package com.yumi.platform.aftercommit.api;

import java.util.List;

/** 提交后任务只读查询：状态、失败原因与来源标识。 */
public interface AfterCommitTaskQuery {

    AfterCommitTaskView getTask(String taskBusinessId);

    List<AfterCommitTaskView> findByStatus(AfterCommitTaskStatus status, int limit);
}

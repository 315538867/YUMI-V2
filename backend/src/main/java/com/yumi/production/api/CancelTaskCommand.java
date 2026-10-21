package com.yumi.production.api;

/** 取消尚未核验的任务安排；已核验事实保留。 */
public record CancelTaskCommand(String taskBusinessId, String reasonText) {

    public CancelTaskCommand {
        if (taskBusinessId == null || taskBusinessId.isBlank()) {
            throw new IllegalArgumentException("任务标识不能为空");
        }
        if (reasonText == null || reasonText.isBlank()) {
            throw new IllegalArgumentException("任务取消必须填写原因");
        }
    }
}

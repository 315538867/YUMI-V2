package com.yumi.production.api;

import java.util.List;

/**
 * 核验命令：一次提交一条或多条尚未核验的任务明细的最终结果。
 *
 * @param items 被选中的明细；每条只能提交一次最终结果
 */
public record VerifyTaskCommand(List<TaskItemResultRequest> items) {

    public VerifyTaskCommand {
        items = List.copyOf(items == null ? List.of() : items);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("核验必须至少包含一条任务明细");
        }
    }
}

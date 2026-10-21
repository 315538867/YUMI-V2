package com.yumi.production.api;

/**
 * 生产事实更正命令（基线 11 §7.8）。
 *
 * <p>只对已经核验的明细发起；更正不删除历史，而是写入反向事实与替代事实，并在同一命令批次内
 * 发布劳动、材料与责任的 REVERSE / APPLY 项。已核验的标准负载始终留在原工作日期。
 *
 * @param taskItemBusinessId 被更正的任务明细
 * @param correctedResult 更正后的最终核验结果（数量方程必须重新成立）
 * @param reason 更正原因，必填
 */
public record ProductionCorrectionCommand(
        String taskItemBusinessId,
        TaskItemResultRequest correctedResult,
        String reason) {

    public ProductionCorrectionCommand {
        if (taskItemBusinessId == null || taskItemBusinessId.isBlank()) {
            throw new IllegalArgumentException("任务明细标识不能为空");
        }
        if (correctedResult == null) {
            throw new IllegalArgumentException("更正后的核验结果不能为空");
        }
        if (!taskItemBusinessId.equals(correctedResult.taskItemBusinessId())) {
            throw new IllegalArgumentException("更正结果必须指向同一任务明细");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("更正必须填写原因");
        }
    }
}

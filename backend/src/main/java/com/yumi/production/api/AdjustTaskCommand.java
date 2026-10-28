package com.yumi.production.api;

import java.time.LocalDate;
import java.util.List;

/**
 * 调整任务：改期、换主体、改工序或改明细。
 *
 * <p>规则来源：`docs/一期/11-生产作业模块.md` §7.2。调用方只提交调整后的完整计划；
 * 已核验明细不可修改或移除，工序只能在任务从未产生有效核验事实时修改。
 *
 * @param taskBusinessId 原任务标识
 * @param subject 调整后的执行主体
 * @param workDate 调整后的工作日期
 * @param processCode 调整后的工序；其他任务为空
 * @param items 调整后的未核验明细；已核验明细由服务端保留，不应出现在此列表
 * @param enteredStandardMinutes 其他任务整体标准分钟；生产/返工任务为空
 * @param otherTask 其他任务详情；生产/返工任务为空
 * @param overrideReason 超出标准工作量的显式覆盖原因
 */
public record AdjustTaskCommand(
        String taskBusinessId,
        SubjectRef subject,
        LocalDate workDate,
        TargetProcess processCode,
        List<ItemRequest> items,
        Integer enteredStandardMinutes,
        ArrangeTaskCommand.OtherTaskRequest otherTask,
        String overrideReason) {

    public AdjustTaskCommand {
        if (taskBusinessId == null || taskBusinessId.isBlank()) {
            throw new IllegalArgumentException("任务标识不能为空");
        }
        if (subject == null || workDate == null) {
            throw new IllegalArgumentException("执行主体与工作日期不能为空");
        }
        items = List.copyOf(items == null ? List.of() : items);
    }

    /**
     * 调整后的明细。
     *
     * @param taskItemBusinessId 现有未核验明细标识；新增明细为空
     * @param productionSourceBusinessId 调整后的生产来源
     * @param plannedQuantity 调整后的计划数量
     */
    public record ItemRequest(
            String taskItemBusinessId,
            String productionSourceBusinessId,
            String repairSourceBusinessId,
            int plannedQuantity) {

        public ItemRequest(
                String taskItemBusinessId, String productionSourceBusinessId,
                int plannedQuantity) {
            this(taskItemBusinessId, productionSourceBusinessId, null, plannedQuantity);
        }

        public ItemRequest {
            boolean hasProduction = productionSourceBusinessId != null
                    && !productionSourceBusinessId.isBlank();
            boolean hasRepair = repairSourceBusinessId != null
                    && !repairSourceBusinessId.isBlank();
            if (!hasProduction && !hasRepair) {
                throw new IllegalArgumentException("任务明细必须关联履约生产来源或返工来源");
            }
            if (!hasRepair) {
                repairSourceBusinessId = null;
            }
            if (plannedQuantity <= 0) {
                throw new IllegalArgumentException("任务明细计划数量必须大于 0");
            }
        }

        public boolean existing() {
            return taskItemBusinessId != null && !taskItemBusinessId.isBlank();
        }
    }
}

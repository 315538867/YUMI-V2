package com.yumi.production.overtime;

import java.time.LocalDate;
import java.util.List;

/** 超额任务读模型与入参（阶段五）：预占不修改未来计划原始数量、不产生工序流入或完成。 */
public final class OvertimeTaskViews {

    private OvertimeTaskViews() {
    }

    public record PreemptionView(
            long id,
            long futurePlanId,
            String futurePlanNo,
            LocalDate futurePlanDate,
            int preemptedQuantity,
            String status) {
    }

    public record OvertimeTaskView(
            long planId,
            String planNo,
            String node,
            LocalDate planDate,
            long orderItemId,
            long employeeId,
            String employeeName,
            int quantity,
            String status,
            List<PreemptionView> preemptions) {
    }

    /**
     * 创建超额任务入参：只在执行当天创建，只能从**未来日期**的正常计划选择尚未预占数量；
     * 跨订单/商品时每条仍明确来源计划。计划数量 = 各来源预占数量之和。
     */
    public record CreateOvertimeTaskRequest(
            Long orderItemId,
            String node,
            LocalDate planDate,
            Long employeeId,
            List<Line> lines,
            String note) {

        public record Line(Long futurePlanId, Integer quantity) {
        }
    }
}

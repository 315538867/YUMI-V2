package com.yumi.production.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 订单项到实际生产事实的追溯。
 *
 * <p>只输出已经发生的事实：没有核验就没有完成数量，计划数量不会被当成完成数量。缺口与延迟
 * 以可识别的原因列表给出，供排班与运营排查。
 */
public record OrderProductionTrace(
        String orderBusinessId,
        String orderItemBusinessId,
        List<DemandTrace> demands) {

    public OrderProductionTrace {
        demands = List.copyOf(demands);
    }

    /** 单个生产需求的追溯。 */
    public record DemandTrace(
            String demandBusinessId,
            DemandType demandType,
            String productionSourceBusinessId,
            String physicalRootSourceBusinessId,
            String productBusinessId,
            String productSnapshotRef,
            String schemeSnapshotRef,
            TargetProcess targetProcess,
            String status,
            ProductionQuantityLedger ledger,
            List<TaskTrace> tasks,
            List<FactTrace> facts,
            List<String> gapReasons,
            List<String> delayReasons) {

        public DemandTrace {
            tasks = List.copyOf(tasks);
            facts = List.copyOf(facts);
            gapReasons = List.copyOf(gapReasons);
            delayReasons = List.copyOf(delayReasons);
        }
    }

    /** 任务追溯：保留原工作日期与核验时间两个不同口径。 */
    public record TaskTrace(
            String taskBusinessId,
            String taskNo,
            String taskType,
            LocalDate workDate,
            String processCode,
            String subjectType,
            String subjectBusinessId,
            int plannedQuantity,
            long taskStandardSeconds,
            String status,
            List<ItemTrace> items) {

        public TaskTrace {
            items = List.copyOf(items);
        }
    }

    /** 任务明细追溯。 */
    public record ItemTrace(
            String taskItemBusinessId,
            String productionSourceBusinessId,
            DemandType demandType,
            int plannedQuantity,
            int completedQuantity,
            int unfinishedQuantity,
            int qualifiedQuantity,
            int reworkQuantity,
            int scrappedQuantity,
            String verificationStatus) {
    }

    /** 实际事实追溯：每类事实都带来源与发生时间，便于识别缺口与延迟。 */
    public record FactTrace(
            String factKind,
            String factBusinessId,
            String productionSourceBusinessId,
            String physicalRootSourceBusinessId,
            String taskBusinessId,
            LocalDate taskWorkDate,
            Instant occurredAt,
            int quantity,
            String detail) {
    }
}

package com.yumi.production.application.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 追溯与公开投影读取端口（只读）。 */
public interface TraceRepository {

    Optional<String> findOrderBusinessIdOfDemand(String demandBusinessId);

    List<DemandRecord> findDemandsOfOrderItem(String orderItemBusinessId);

    /** 有生产需求的订单项，按最近更新倒序；用于默认展示全部订单追溯。 */
    List<String> listOrderItemsWithDemands(int offset, int limit);

    /** 整体进度按需求的聚合行。 */
    List<OverviewDemandRow> overviewDemands();

    List<TaskRow> findTasksOfSource(String productionSourceBusinessId);

    List<ItemRow> findItemsOfSource(String productionSourceBusinessId);

    List<FactRow> findFactsOfSource(String productionSourceBusinessId);

    /** 未核验计划数量（PENDING 明细的计划数量合计）。 */
    int pendingVerificationQuantityOfSource(String productionSourceBusinessId);

    /** 延迟核验数量：核验日期晚于任务工作日期。 */
    int delayedVerificationCountOfSource(String productionSourceBusinessId);

    List<ProjectionRow> listProjections(Instant updatedAfter, String afterDemandBusinessId, int limit);

    List<ProjectionRow> projectionsOfProduct(String productBusinessId, int limit);

    Optional<ProjectionRow> findProjection(String demandBusinessId);

    /** 任务行。 */
    record TaskRow(
            String taskBusinessId, String taskNo, String taskType, LocalDate workDate, String processCode,
            String subjectType, String subjectBusinessId, int plannedQuantity, long taskStandardSeconds,
            String status, String productionSourceBusinessId) {
    }

    /** 明细行。 */
    record ItemRow(
            String taskItemBusinessId, String taskBusinessId, String productionSourceBusinessId,
            com.yumi.production.api.DemandType demandType, int plannedQuantity, Integer completedQuantity,
            Integer unfinishedQuantity, Integer qualifiedQuantity, Integer reworkQuantity,
            Integer scrappedQuantity, String verificationStatus) {
    }

    /** 实际事实行（执行、质量、缺陷、处置、退出、消费、移交）。 */
    record FactRow(
            String factKind, String factBusinessId, String productionSourceBusinessId,
            String physicalRootSourceBusinessId, String taskBusinessId, LocalDate taskWorkDate,
            Instant occurredAt, int quantity, String detail) {
    }

    /** 整体进度聚合行：需求、履约与三道工序的排班/完成数量。 */
    record OverviewDemandRow(
            String demandBusinessId, String orderBusinessId,
            com.yumi.production.api.TargetProcess targetProcess,
            int requiredQuantity, int fulfilledQuantity,
            int scheduledMaking, int scheduledBagging, int scheduledSeam,
            int processedMaking, int processedBagging, int processedSeam,
            int completedMaking, int completedBagging, int completedSeam) {
    }

    /** 公开投影行。 */
    record ProjectionRow(
            String demandBusinessId, String productionSourceBusinessId, String orderItemBusinessId,
            String productBusinessId, com.yumi.production.api.DemandType demandType,
            int requiredQuantity, int pendingWorkQuantity, int inProcessQuantity,
            int targetReadyQuantity, int fulfilledQuantity, int fulfillmentGapQuantity,
            int cumulativeMakingCreatedQuantity, int inSystemQuantity, int consumedQuantity,
            int destroyedQuantity, int offlineInventoryQuantity, Instant updatedAt) {
    }
}

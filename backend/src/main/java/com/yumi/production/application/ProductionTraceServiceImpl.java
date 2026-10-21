package com.yumi.production.application;

import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.production.api.DemandType;
import com.yumi.production.api.OrderProductionTrace;
import com.yumi.production.api.ProductionProjectionPage;
import com.yumi.production.api.ProductionProjectionRow;
import com.yumi.production.api.ProductionQuantityLedger;
import com.yumi.production.api.ProductionQuery;
import com.yumi.production.api.ProductionReportingFeed;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.application.port.DemandRecord;
import com.yumi.production.application.port.ProductionRepository;
import com.yumi.production.application.port.QuantityProjections;
import com.yumi.production.application.port.SourceRecord;
import com.yumi.production.application.port.TraceRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 生产公开查询与投影实现（基线 11 §5.2.1、§7.7、§9.3）。
 *
 * <p>读模型只输出已经发生的事实：未核验的计划数量单独标记，不折算成完成数量；缺口与延迟以
 * 可识别原因输出。两套数量账同时给出并自检守恒。
 */
public class ProductionTraceServiceImpl implements ProductionQuery, ProductionReportingFeed {

    private static final int MAX_LIMIT = 200;

    private final TraceRepository traces;
    private final ProductionRepository production;
    private final AuthorizationService authorization;

    public ProductionTraceServiceImpl(
            TraceRepository traces, ProductionRepository production, AuthorizationService authorization) {
        this.traces = traces;
        this.production = production;
        this.authorization = authorization;
    }

    @Override
    public OrderProductionTrace traceOfOrderItem(String orderItemBusinessId) {
        authorization.require(PermissionCodes.PRODUCTION_READ, ResourceScope.GLOBAL);
        if (orderItemBusinessId == null || orderItemBusinessId.isBlank()) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_ORDER_ITEM", "订单项标识不能为空");
        }
        List<DemandRecord> demands = traces.findDemandsOfOrderItem(orderItemBusinessId);
        if (demands.isEmpty()) {
            throw new ApiException(
                    ApiErrorType.NOT_FOUND, "PRODUCTION_DEMAND_NOT_FOUND", "该订单项没有生产需求");
        }
        String orderBusinessId = demands.getFirst().orderBusinessId();
        List<OrderProductionTrace.DemandTrace> tracesOfDemand = new ArrayList<>();
        for (DemandRecord demand : demands) {
            tracesOfDemand.add(traceOfDemand(demand));
        }
        return new OrderProductionTrace(orderBusinessId, orderItemBusinessId, tracesOfDemand);
    }

    @Override
    public ProductionQuantityLedger ledgerOfDemand(String demandBusinessId) {
        authorization.require(PermissionCodes.PRODUCTION_READ, ResourceScope.GLOBAL);
        DemandRecord demand = production.findDemand(demandBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_DEMAND_NOT_FOUND", "生产需求不存在"));
        List<SourceRecord> sources = production.findSourcesOfDemand(demandBusinessId);
        SourceRecord source = sources.isEmpty() ? null : sources.getFirst();
        return ledgerOf(demand, source, production.findDemandProjection(demandBusinessId).orElseThrow(),
                source == null ? null
                        : production.findPhysicalProjection(source.physicalRootSourceBusinessId()).orElse(null));
    }

    @Override
    public List<ProductionProjectionRow> projectionsOfProduct(String productBusinessId, int limit) {
        authorization.require(PermissionCodes.PRODUCTION_READ, ResourceScope.GLOBAL);
        if (productBusinessId == null || productBusinessId.isBlank()) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_PRODUCT", "产品编号不能为空");
        }
        return traces.projectionsOfProduct(productBusinessId, clamp(limit)).stream()
                .map(ProductionTraceServiceImpl::toRow)
                .toList();
    }

    @Override
    public ProductionProjectionPage listAfter(
            Instant updatedAfter, String afterDemandBusinessId, int limit) {
        authorization.require(PermissionCodes.PRODUCTION_READ, ResourceScope.GLOBAL);
        int size = clamp(limit);
        var rows = traces.listProjections(updatedAfter, afterDemandBusinessId, size);
        List<ProductionProjectionRow> page = rows.stream()
                .map(ProductionTraceServiceImpl::toRow)
                .toList();
        if (rows.size() < size) {
            return new ProductionProjectionPage(page, null, null);
        }
        var last = rows.getLast();
        return new ProductionProjectionPage(page, last.updatedAt(), last.demandBusinessId());
    }

    private OrderProductionTrace.DemandTrace traceOfDemand(DemandRecord demand) {
        List<SourceRecord> sources = production.findSourcesOfDemand(demand.demandBusinessId());
        SourceRecord source = sources.isEmpty() ? null : sources.getFirst();
        var demandProjection = production.findDemandProjection(demand.demandBusinessId()).orElseThrow();
        var physical = source == null ? null
                : production.findPhysicalProjection(source.physicalRootSourceBusinessId()).orElse(null);
        ProductionQuantityLedger ledger = ledgerOf(demand, source, demandProjection, physical);

        List<OrderProductionTrace.TaskTrace> taskTraces = new ArrayList<>();
        List<OrderProductionTrace.FactTrace> facts = new ArrayList<>();
        List<String> gapReasons = new ArrayList<>();
        List<String> delayReasons = new ArrayList<>();
        int pendingVerification = 0;
        if (source != null) {
            var items = traces.findItemsOfSource(source.productionSourceBusinessId());
            for (var task : traces.findTasksOfSource(source.productionSourceBusinessId())) {
                List<OrderProductionTrace.ItemTrace> itemTraces = items.stream()
                        .filter(item -> item.taskBusinessId().equals(task.taskBusinessId()))
                        .map(item -> new OrderProductionTrace.ItemTrace(
                                item.taskItemBusinessId(), item.productionSourceBusinessId(),
                                item.demandType(), item.plannedQuantity(),
                                item.completedQuantity() == null ? 0 : item.completedQuantity(),
                                item.unfinishedQuantity() == null ? 0 : item.unfinishedQuantity(),
                                item.qualifiedQuantity() == null ? 0 : item.qualifiedQuantity(),
                                item.reworkQuantity() == null ? 0 : item.reworkQuantity(),
                                item.scrappedQuantity() == null ? 0 : item.scrappedQuantity(),
                                item.verificationStatus()))
                        .toList();
                taskTraces.add(new OrderProductionTrace.TaskTrace(
                        task.taskBusinessId(), task.taskNo(), task.taskType(), task.workDate(),
                        task.processCode(), task.subjectType(), task.subjectBusinessId(),
                        task.plannedQuantity(), task.taskStandardSeconds(), task.status(), itemTraces));
            }
            pendingVerification = traces.pendingVerificationQuantityOfSource(
                    source.productionSourceBusinessId());
            if (pendingVerification > 0) {
                delayReasons.add("存在未核验计划数量 " + pendingVerification + " 件，不计入完成数量");
            }
            for (var fact : traces.findFactsOfSource(source.productionSourceBusinessId())) {
                facts.add(new OrderProductionTrace.FactTrace(
                        fact.factKind(), fact.factBusinessId(), fact.productionSourceBusinessId(),
                        fact.physicalRootSourceBusinessId(), fact.taskBusinessId(), fact.taskWorkDate(),
                        fact.occurredAt(), fact.quantity(), fact.detail()));
                if (fact.taskWorkDate() != null && fact.occurredAt() != null
                        && fact.occurredAt().atZone(java.time.ZoneOffset.UTC).toLocalDate()
                                .isAfter(fact.taskWorkDate())) {
                    delayReasons.add("核验日期晚于任务工作日期：" + fact.taskBusinessId());
                }
            }
            int delayed = traces.delayedVerificationCountOfSource(source.productionSourceBusinessId());
            if (delayed > 0 && delayReasons.stream().noneMatch(reason -> reason.startsWith("核验日期"))) {
                delayReasons.add("存在 " + delayed + " 条延迟核验记录");
            }
        }
        if (demandProjection.fulfillmentGapQuantity() > 0) {
            gapReasons.add("履约缺口 " + demandProjection.fulfillmentGapQuantity() + " 件");
            gapReasons.add(demand.demandType() == DemandType.RETURNED_PRODUCT_REPAIR
                    ? "修复终止缺口只能由售后义务变更消除"
                    : "新制补产缺口只能由正常补产或上游减少需求消除");
        }
        if (demandProjection.pendingWorkQuantity() > 0 && taskTraces.isEmpty()) {
            gapReasons.add("尚有 " + demandProjection.pendingWorkQuantity() + " 件未排产");
        }

        return new OrderProductionTrace.DemandTrace(
                demand.demandBusinessId(), demand.demandType(),
                source == null ? null : source.productionSourceBusinessId(),
                source == null ? null : source.physicalRootSourceBusinessId(),
                demand.productBusinessId(), demand.productSnapshotRef(), demand.schemeSnapshotRef(),
                demand.targetProcess(), demand.status().name(), ledger, taskTraces, facts,
                gapReasons, delayReasons);
    }

    private static ProductionQuantityLedger ledgerOf(
            DemandRecord demand, SourceRecord source, QuantityProjections.DemandProjection projection,
            QuantityProjections.PhysicalProjection physical) {
        return new ProductionQuantityLedger(
                demand.demandBusinessId(),
                source == null ? null : source.productionSourceBusinessId(),
                demand.demandType(),
                projection.requiredQuantity(),
                projection.pendingWorkQuantity(),
                projection.inProcessQuantity(),
                projection.targetReadyQuantity(),
                projection.fulfilledQuantity(),
                projection.fulfillmentGapQuantity(),
                physical == null ? null : new ProductionQuantityLedger.PhysicalLedger(
                        physical.physicalRootSourceBusinessId(),
                        physical.cumulativeMakingCreatedQuantity(),
                        physical.inSystemQuantity(),
                        physical.consumedQuantity(),
                        physical.destroyedQuantity(),
                        physical.offlineInventoryQuantity()));
    }

    private static ProductionProjectionRow toRow(TraceRepository.ProjectionRow row) {
        return new ProductionProjectionRow(
                row.demandBusinessId(), row.productionSourceBusinessId(), row.orderItemBusinessId(),
                row.productBusinessId(), row.demandType(), row.requiredQuantity(),
                row.pendingWorkQuantity(), row.inProcessQuantity(), row.targetReadyQuantity(),
                row.fulfilledQuantity(), row.fulfillmentGapQuantity(), 0, 0,
                row.cumulativeMakingCreatedQuantity(), row.inSystemQuantity(), row.consumedQuantity(),
                row.destroyedQuantity(), row.offlineInventoryQuantity(), row.updatedAt());
    }

    private static int clamp(int limit) {
        if (limit <= 0) {
            throw new ApiException(ApiErrorType.VALIDATION, "INVALID_LIMIT", "读取条数必须大于 0");
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /** 供测试识别：需求目标工序是否已经达到。 */
    static boolean reachedTarget(TargetProcess target, TargetProcess process) {
        return target.chainTo().contains(process);
    }
}

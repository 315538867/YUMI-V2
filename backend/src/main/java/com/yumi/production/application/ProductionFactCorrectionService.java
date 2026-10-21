package com.yumi.production.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.value.ContentFingerprint;
import com.yumi.production.api.DefectGroupRequest;
import com.yumi.production.api.DemandType;
import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.ProductionCorrectionCommand;
import com.yumi.production.api.ProductionCorrectionFeedItem;
import com.yumi.production.api.ProductionCorrectionQuery;
import com.yumi.production.api.ProductionCorrectionResult;
import com.yumi.production.api.ProductionCorrectionService;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.api.TaskItemResultRequest;
import com.yumi.production.api.VerificationView;
import com.yumi.production.application.port.CorrectionRepository;
import com.yumi.production.application.port.DemandRecord;
import com.yumi.production.application.port.ProductionRepository;
import com.yumi.production.application.port.TaskItemRecord;
import com.yumi.production.application.port.TaskRecord;
import com.yumi.production.application.port.TaskRepository;
import com.yumi.production.application.port.VerificationRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 生产事实更正实现（基线 11 §7.8）。
 *
 * <p>锁序与核验一致。旧事实全部置为无效（保留历史），随后按更正后的结果写入替代事实；
 * 需求与物理投影按“先反向旧增量、再叠加新增量”的净差调整；已核验标准负载始终结算到原任务
 * 工作日期与主体，不搬到核验当天。
 */
public class ProductionFactCorrectionService
        implements ProductionCorrectionService, ProductionCorrectionQuery {

    private static final String FEED_CODE = "production.fact-correction";

    private final TaskRepository tasks;
    private final ProductionRepository production;
    private final VerificationRepository verifications;
    private final CorrectionRepository corrections;
    private final DailyLoadService dailyLoads;
    private final NewProductCapacityService capacity;
    private final AuthorizationService authorization;
    private final CurrentActorProvider currentActor;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public ProductionFactCorrectionService(
            TaskRepository tasks,
            ProductionRepository production,
            VerificationRepository verifications,
            CorrectionRepository corrections,
            DailyLoadService dailyLoads,
            NewProductCapacityService capacity,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.tasks = tasks;
        this.production = production;
        this.verifications = verifications;
        this.corrections = corrections;
        this.dailyLoads = dailyLoads;
        this.capacity = capacity;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public ProductionCorrectionResult correct(ProductionCorrectionCommand command) {
        authorization.require(PermissionCodes.PRODUCTION_CORRECT, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_CORRECTION", "更正命令不能为空");
        }
        return transactionTemplate.execute(status -> doCorrect(command));
    }

    @Override
    public List<ProductionCorrectionFeedItem> listAfter(long feedSequence, int limit) {
        authorization.require(PermissionCodes.PRODUCTION_READ, ResourceScope.GLOBAL);
        if (limit <= 0) {
            throw new ApiException(ApiErrorType.VALIDATION, "INVALID_LIMIT", "读取条数必须大于 0");
        }
        return corrections.listAfter(feedSequence, limit).stream()
                .map(row -> new ProductionCorrectionFeedItem(
                        row.feedSequence(), row.correctionCommandBusinessId(),
                        row.taskItemBusinessId(), row.itemKind(), row.effectType(),
                        row.referencedFactBusinessId(), row.quantity(), row.businessDate()))
                .toList();
    }

    @Override
    public List<ProductionCorrectionFeedItem> itemsOf(String correctionCommandBusinessId) {
        authorization.require(PermissionCodes.PRODUCTION_READ, ResourceScope.GLOBAL);
        var correction = corrections.findCorrection(correctionCommandBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_CORRECTION_NOT_FOUND", "更正命令不存在"));
        return corrections.findItems(correctionCommandBusinessId).stream()
                .map(row -> new ProductionCorrectionFeedItem(
                        correction.feedSequence(), row.correctionCommandBusinessId(),
                        correction.taskItemBusinessId(), row.itemKind(), row.effectType(),
                        row.referencedFactBusinessId(), row.quantity(), row.businessDate()))
                .toList();
    }

    private ProductionCorrectionResult doCorrect(ProductionCorrectionCommand command) {
        TaskItemRecord item = tasks.findTaskItem(command.taskItemBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_TASK_ITEM_NOT_FOUND", "任务明细不存在"));
        TaskRecord task = tasks.findTask(item.taskBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_TASK_NOT_FOUND", "任务不存在"));
        DemandRecord demand = production.findDemand(item.demandBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_DEMAND_NOT_FOUND", "生产需求不存在"));

        var previous = verifications.findItemVerification(command.taskItemBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_CORRECTION_NOT_APPLICABLE",
                        "该明细尚未核验，没有可更正的事实"));

        TaskItemResultRequest corrected = command.correctedResult();
        requireQuantityEquation(item, corrected);
        requireDefects(corrected);

        Instant now = clock.instant();
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
        String commandId = "CRN-" + UUID.randomUUID();

        // 1. 反向旧事实：保留历史，只置为无效
        int oldLaborQuantity = verifications.sumLaborQuantityOfTask(task.taskBusinessId());
        int oldMaterialQuantity = verifications.sumMaterialQuantityOfItem(item.taskItemBusinessId());
        int oldResponsibilityQuantity = verifications.sumResponsibilityQuantityOfItem(
                item.taskItemBusinessId());
        verifications.invalidateLaborSourcesOfTask(task.taskBusinessId());
        verifications.invalidateFactsOfItem(item.taskItemBusinessId());

        // 2. 净差调整需求与物理投影：先撤销旧增量，再写入新增量
        applyProjectionDelta(task, item, demand, previous, -1);
        applyProjectionDelta(task, item, demand, corrected, +1);

        // 3. 写入替代事实
        var written = writeSubstituteFacts(task, item, demand, corrected, now, operatorId, commandId);

        // 4. 负载与产能：只改原工作日期，不搬移
        long plannedBase = (long) item.plannedQuantity() * item.itemStandardMinutes() * 60L;
        long oldCompletedBase = (long) previous.completedQuantity() * item.itemStandardMinutes() * 60L;
        long newCompletedBase = (long) corrected.completedQuantity() * item.itemStandardMinutes() * 60L;
        long plannedLoad = task.taskStandardSeconds();
        dailyLoads.movePlanToCommitted(
                subjectEmployeeId(task), task.workDate(), 0L,
                -scaled(plannedLoad, plannedBase, oldCompletedBase)
                        + scaled(plannedLoad, plannedBase, newCompletedBase));
        if (task.processCode() == TargetProcess.MAKING
                && item.demandType() == DemandType.NEW_PRODUCT) {
            var lock = capacity.lockKeepingLimit(item.productBusinessId(), task.workDate());
            capacity.movePlanToCommitted(
                    lock, 0, corrected.completedQuantity() - previous.completedQuantity());
        }

        // 5. 命令批次：REVERSE 旧项 + APPLY 新项，构成完整批次
        List<CorrectionRepository.ItemRecord> batchItems = new ArrayList<>();
        int index = 0;
        if (oldLaborQuantity > 0) {
            batchItems.add(new CorrectionRepository.ItemRecord(
                    "CRI-" + UUID.randomUUID(), commandId, index++, "LABOR", "REVERSE",
                    "TASK:" + task.taskBusinessId(), oldLaborQuantity, null, task.workDate()));
        }
        for (var labor : written.laborSourceIds()) {
            batchItems.add(new CorrectionRepository.ItemRecord(
                    "CRI-" + UUID.randomUUID(), commandId, index++, "LABOR", "APPLY",
                    labor, written.laborQuantity(), null, task.workDate()));
        }
        if (oldMaterialQuantity > 0) {
            batchItems.add(new CorrectionRepository.ItemRecord(
                    "CRI-" + UUID.randomUUID(), commandId, index++, "MATERIAL", "REVERSE",
                    "ITEM:" + item.taskItemBusinessId(), oldMaterialQuantity, null, task.workDate()));
        }
        for (var material : written.materialSourceIds()) {
            batchItems.add(new CorrectionRepository.ItemRecord(
                    "CRI-" + UUID.randomUUID(), commandId, index++, "MATERIAL", "APPLY",
                    material, corrected.completedQuantity(), null, task.workDate()));
        }
        if (oldResponsibilityQuantity > 0) {
            batchItems.add(new CorrectionRepository.ItemRecord(
                    "CRI-" + UUID.randomUUID(), commandId, index++, "RESPONSIBILITY", "REVERSE",
                    "ITEM:" + item.taskItemBusinessId(), oldResponsibilityQuantity, null, task.workDate()));
        }
        for (var responsibility : written.responsibilityIds()) {
            batchItems.add(new CorrectionRepository.ItemRecord(
                    "CRI-" + UUID.randomUUID(), commandId, index++, "RESPONSIBILITY", "APPLY",
                    responsibility.id(), responsibility.quantity(), null, task.workDate()));
        }
        if (batchItems.isEmpty()) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_CORRECTION_EMPTY_BATCH",
                    "更正命令没有产生任何来源项");
        }

        String fingerprint = fingerprintOf(command, batchItems);
        corrections.insertCorrection(new CorrectionRepository.CorrectionRecord(
                commandId, item.taskItemBusinessId(), task.taskBusinessId(), demand.demandBusinessId(),
                item.productionSourceBusinessId(), previous.taskItemBusinessId(), command.reason().strip(),
                batchItems.size(), fingerprint, now, operatorId, 0L));
        batchItems.forEach(corrections::insertItem);
        corrections.markApplied(commandId, now);

        // 6. 更正后任务状态：明细仍为已核验
        verifications.updateTaskItemVerification(
                item.taskItemBusinessId(), corrected.completedQuantity(), corrected.unfinishedQuantity(),
                corrected.qualifiedQuantity(), corrected.reworkQuantity(), corrected.scrappedQuantity(),
                corrected.destroyedQuantity(), corrected.scrapOfflineQuantity(), "VERIFIED");

        boolean responsibilityReversedOnly = oldResponsibilityQuantity > 0
                && written.responsibilityIds().isEmpty();

        return new ProductionCorrectionResult(
                commandId, item.taskItemBusinessId(), fingerprint, batchItems.size(),
                batchItems.stream().map(row -> new ProductionCorrectionResult.BatchItem(
                        row.itemIndex(), row.itemKind(), row.effectType(),
                        row.referencedFactBusinessId(), row.quantity(),
                        row.businessDate().toString())).toList(),
                new VerificationView.ItemVerificationView(
                        item.taskItemBusinessId(), demand.demandBusinessId(),
                        item.productionSourceBusinessId(), item.plannedQuantity(),
                        corrected.unfinishedQuantity(), corrected.completedQuantity(),
                        corrected.qualifiedQuantity(), corrected.reworkQuantity(),
                        corrected.scrappedQuantity(), corrected.destroyedQuantity(),
                        corrected.scrapOfflineQuantity(), List.of(), written.repairSourceIds()),
                responsibilityReversedOnly);
    }

    // ------------------------------------------------------------------
    // 数量方程与缺陷校验（与核验同一口径）
    // ------------------------------------------------------------------

    private static void requireQuantityEquation(TaskItemRecord item, TaskItemResultRequest request) {
        if (request.completedQuantity() + request.unfinishedQuantity() != item.plannedQuantity()) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCTION_QUANTITY_MISMATCH",
                    "计划数量必须等于未完成与完成数量之和");
        }
        if (request.qualifiedQuantity() + request.reworkQuantity() + request.scrappedQuantity()
                != request.completedQuantity()) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCTION_QUANTITY_MISMATCH",
                    "完成数量必须等于合格、返工与报废数量之和");
        }
    }

    private void requireDefects(TaskItemResultRequest request) {
        int defectTotal = request.defects().stream().mapToInt(DefectGroupRequest::quantity).sum();
        int reworkTotal = request.defects().stream().mapToInt(DefectGroupRequest::reworkQuantity).sum();
        int scrapTotal = request.defects().stream().mapToInt(DefectGroupRequest::scrappedQuantity).sum();
        if (defectTotal != request.reworkQuantity() + request.scrappedQuantity()
                || reworkTotal != request.reworkQuantity()
                || scrapTotal != request.scrappedQuantity()) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCTION_QUANTITY_MISMATCH",
                    "缺陷数量合计必须等于返工与线上报废之和");
        }
        Set<String> reasons = new LinkedHashSet<>();
        for (DefectGroupRequest defect : request.defects()) {
            if (!reasons.add(defect.defectReasonCode())) {
                throw new ApiException(
                        ApiErrorType.VALIDATION, "PRODUCTION_DEFECT_INVALID",
                        "同一明细内缺陷原因不能重复：" + defect.defectReasonCode());
            }
            var reason = verifications.findDefectReason(defect.defectReasonCode())
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.VALIDATION, "PRODUCTION_DEFECT_INVALID",
                            "未知缺陷原因：" + defect.defectReasonCode()));
            if (!"ACTIVE".equals(reason.status())) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_DEFECT_INVALID",
                        "缺陷原因已停用，不能用于新核验：" + defect.defectReasonCode());
            }
        }
    }

    // ------------------------------------------------------------------
    // 投影净差
    // ------------------------------------------------------------------

    /**
     * 叠加一份核验结果的投影增量。
     *
     * @param sign {@code +1} 表示应用该结果，{@code -1} 表示反向该结果
     */
    private void applyProjectionDelta(
            TaskRecord task, TaskItemRecord item, DemandRecord demand, Object result, int sign) {
        int completed;
        int qualified;
        int rework;
        int scrapped;
        if (result instanceof VerificationRepository.ItemVerificationRecord previous) {
            completed = previous.completedQuantity();
            qualified = previous.qualifiedQuantity();
            rework = previous.reworkQuantity();
            scrapped = previous.scrappedQuantity();
        } else {
            TaskItemResultRequest request = (TaskItemResultRequest) result;
            completed = request.completedQuantity();
            qualified = request.qualifiedQuantity();
            rework = request.reworkQuantity();
            scrapped = request.scrappedQuantity();
        }

        boolean atMaking = task.processCode() == TargetProcess.MAKING;
        boolean atTarget = task.processCode() == demand.targetProcess();

        var current = production.findDemandProjection(demand.demandBusinessId()).orElseThrow();
        int pending = current.pendingWorkQuantity() + sign * (atMaking ? -completed : 0);
        int inProcess = current.inProcessQuantity()
                + sign * (atMaking ? 0 : -completed)
                + sign * qualified
                + sign * rework;
        int targetReady = current.targetReadyQuantity() + sign * (atTarget ? qualified : 0);
        int gap = current.fulfillmentGapQuantity() + sign * scrapped;
        if (atTarget) {
            // 目标工序的合格进入目标就绪，不再计入在制
            inProcess -= sign * qualified;
        }
        if (pending < 0 || inProcess < 0 || targetReady < 0 || gap < 0) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_PROJECTION_INCONSISTENT",
                    "数量投影出现负数，不能应用更正");
        }
        verifications.updateDemandProjection(demand.demandBusinessId(), pending, inProcess, targetReady, gap);

        if (item.demandType() != DemandType.NEW_PRODUCT) {
            return;
        }
        var physical = production.findPhysicalProjection(item.physicalRootSourceBusinessId())
                .orElseGet(() -> new com.yumi.production.application.port.QuantityProjections
                        .PhysicalProjection(item.physicalRootSourceBusinessId(), 0, 0, 0, 0, 0, 1L));
        int cumulativeDelta = atMaking ? sign * completed : 0;
        int inSystemDelta = atMaking ? sign * (qualified + rework) : 0;
        int destroyedDelta = sign * destroyedOf(result);
        int offlineDelta = sign * offlineOf(result);
        if (!atMaking) {
            inSystemDelta -= destroyedDelta + offlineDelta;
        }
        var updated = new com.yumi.production.application.port.QuantityProjections.PhysicalProjection(
                item.physicalRootSourceBusinessId(),
                physical.cumulativeMakingCreatedQuantity() + cumulativeDelta,
                physical.inSystemQuantity() + inSystemDelta,
                physical.consumedQuantity(),
                physical.destroyedQuantity() + destroyedDelta,
                physical.offlineInventoryQuantity() + offlineDelta,
                physical.rowVersion() + 1);
        production.insertPhysicalProjection(updated);
    }

    private static int destroyedOf(Object result) {
        if (result instanceof VerificationRepository.ItemVerificationRecord previous) {
            return previous.destroyedQuantity();
        }
        return ((TaskItemResultRequest) result).destroyedQuantity();
    }

    private static int offlineOf(Object result) {
        if (result instanceof VerificationRepository.ItemVerificationRecord previous) {
            return previous.scrapOfflineQuantity();
        }
        return ((TaskItemResultRequest) result).scrapOfflineQuantity();
    }

    // ------------------------------------------------------------------
    // 替代事实
    // ------------------------------------------------------------------

    private WrittenFacts writeSubstituteFacts(
            TaskRecord task, TaskItemRecord item, DemandRecord demand, TaskItemResultRequest request,
            Instant now, String operatorId, String commandId) {
        verifications.insertExecution(new VerificationRepository.ExecutionFact(
                "EXF-" + UUID.randomUUID(), task.taskBusinessId(), item.taskItemBusinessId(),
                demand.demandBusinessId(), demand.demandType(), item.productionSourceBusinessId(),
                item.physicalRootSourceBusinessId(), item.productBusinessId(), item.productSnapshotRef(),
                item.schemeBusinessId(), item.schemeSnapshotRef(), task.processCode().name(),
                task.subject().subjectType().name(), task.subject().subjectBusinessId(),
                request.completedQuantity(), task.workDate(), now));
        verifications.insertQuality(new VerificationRepository.QualityFact(
                "QLF-" + UUID.randomUUID(), item.taskItemBusinessId(), "QUALIFIED",
                request.qualifiedQuantity(), now));
        int unqualified = request.reworkQuantity() + request.scrappedQuantity();
        if (unqualified > 0) {
            verifications.insertQuality(new VerificationRepository.QualityFact(
                    "QLF-" + UUID.randomUUID(), item.taskItemBusinessId(), "UNQUALIFIED", unqualified, now));
        }

        List<String> repairSourceIds = new ArrayList<>();
        List<ResponsibilityRef> responsibilityIds = new ArrayList<>();
        for (DefectGroupRequest defect : request.defects()) {
            var reason = verifications.findDefectReason(defect.defectReasonCode()).orElseThrow();
            String defectFactId = "DFF-" + UUID.randomUUID();
            verifications.insertDefect(new VerificationRepository.DefectFact(
                    defectFactId, item.taskItemBusinessId(), reason.code(), reason.name(),
                    defect.defectReasonText(), defect.quantity(), now));
            if (defect.reworkQuantity() > 0) {
                String repairSourceId = "RPS-" + UUID.randomUUID();
                verifications.insertRepairSource(new VerificationRepository.RepairSourceRecord(
                        repairSourceId, "INTERNAL_DEFECT", "production", "TASK_ITEM_DISPOSITION",
                        defectFactId, demand.demandBusinessId(), item.productionSourceBusinessId(),
                        item.physicalRootSourceBusinessId(), defectFactId, task.processCode().name(),
                        defect.reworkQuantity()));
                verifications.insertRepairSourceFact(
                        repairSourceId, "CREATED", defect.reworkQuantity(), task.taskBusinessId(),
                        item.taskItemBusinessId(), now, operatorId);
                verifications.insertDisposition(new VerificationRepository.DispositionFact(
                        "DSF-" + UUID.randomUUID(), defectFactId, item.taskItemBusinessId(),
                        "REWORK_REQUIRED", PhysicalDestination.IN_SYSTEM, defect.reworkQuantity(),
                        null, repairSourceId, now));
                repairSourceIds.add(repairSourceId);
            }
            for (DefectGroupRequest.ScrapPartRequest part : defect.scrapParts()) {
                String exitId = "TEX-" + UUID.randomUUID();
                verifications.insertTerminalExit(new VerificationRepository.TerminalExit(
                        exitId, item.productionSourceBusinessId(), item.physicalRootSourceBusinessId(),
                        demand.demandBusinessId(), item.taskItemBusinessId(), "QUALITY_SCRAP",
                        part.physicalDestination(), part.quantity(), now));
                String dispositionId = "DSF-" + UUID.randomUUID();
                verifications.insertDisposition(new VerificationRepository.DispositionFact(
                        dispositionId, defectFactId, item.taskItemBusinessId(), "SCRAPPED",
                        part.physicalDestination(), part.quantity(), exitId, null, now));
                String chainId = "RSP-" + UUID.randomUUID();
                verifications.insertResponsibility(new VerificationRepository.ResponsibilityFact(
                        chainId, dispositionId, exitId, part.responsibilityType().name(),
                        part.responsibleSubjectBusinessId(), part.waivedReason(), part.quantity(),
                        task.workDate(), now));
                responsibilityIds.add(new ResponsibilityRef(chainId, part.quantity()));
            }
        }

        List<String> materialSourceIds = new ArrayList<>();
        if (request.completedQuantity() > 0) {
            for (String component : materialComponents(demand)) {
                String materialId = "MCS-" + UUID.randomUUID();
                verifications.insertMaterialCostSource(new VerificationRepository.MaterialCostSource(
                        materialId, task.taskBusinessId(), item.taskItemBusinessId(),
                        demand.demandBusinessId(), item.productionSourceBusinessId(),
                        item.physicalRootSourceBusinessId(), item.productBusinessId(),
                        item.productSnapshotRef(), item.schemeBusinessId(), item.schemeSnapshotRef(),
                        component, task.processCode().name(), request.completedQuantity(),
                        task.workDate(), now, "APPLY"));
                materialSourceIds.add(materialId);
            }
        }

        // 生产任务的计件劳动来源：按完成数量重发，供工资按原工作日期归集
        List<String> laborSourceIds = new ArrayList<>();
        if (request.completedQuantity() > 0) {
            String laborId = "LS-" + UUID.randomUUID();
            verifications.insertLaborSource(new VerificationRepository.LaborSource(
                    laborId, task.taskBusinessId(), item.taskItemBusinessId(), demand.demandBusinessId(),
                    item.productionSourceBusinessId(), task.subject().subjectType().name(),
                    task.subject().subjectBusinessId(), task.taskType().name(),
                    task.processCode() == null ? null : task.processCode().name(),
                    request.completedQuantity(), null, task.workDate(), now, "APPLY"));
            laborSourceIds.add(laborId);
        }

        verifications.updateItemVerification(new VerificationRepository.ItemVerificationRecord(
                item.taskItemBusinessId(), task.taskBusinessId(), item.assignmentVersion(),
                item.plannedQuantity(), request.unfinishedQuantity(), request.completedQuantity(),
                request.qualifiedQuantity(), request.reworkQuantity(), request.scrappedQuantity(),
                request.destroyedQuantity(), request.scrapOfflineQuantity(), task.workDate(), now,
                operatorId, request.resultNote()));

        return new WrittenFacts(laborSourceIds, materialSourceIds, repairSourceIds, responsibilityIds,
                request.completedQuantity(), commandId);
    }

    private List<String> materialComponents(DemandRecord demand) {
        try {
            JsonNode basis = objectMapper.readTree(demand.productionBasisJson());
            List<String> components = new ArrayList<>();
            for (JsonNode line : basis.path("costLines")) {
                if ("NON_LABOR_MATERIAL".equals(line.path("nature").asText(""))) {
                    components.add(line.path("componentCode").asText());
                }
            }
            return components;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private String subjectEmployeeId(TaskRecord task) {
        var participants = tasks.findParticipantEmployeeIds(task.taskBusinessId());
        return participants.isEmpty() ? task.subject().subjectBusinessId() : participants.getFirst();
    }

    private static long scaled(long plannedLoad, long plannedBase, long completedBase) {
        return plannedBase == 0 ? 0L : Math.round((double) plannedLoad * completedBase / plannedBase);
    }

    private String fingerprintOf(
            ProductionCorrectionCommand command, List<CorrectionRepository.ItemRecord> items) {
        StringBuilder builder = new StringBuilder(command.taskItemBusinessId())
                .append('|').append(command.reason().strip());
        items.forEach(item -> builder.append('|').append(item.itemIndex()).append(':')
                .append(item.itemKind()).append(':').append(item.effectType()).append(':')
                .append(item.referencedFactBusinessId()).append(':').append(item.quantity()));
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(builder.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法", exception);
        }
    }

    /** 写入的替代事实标识。 */
    private record WrittenFacts(
            List<String> laborSourceIds,
            List<String> materialSourceIds,
            List<String> repairSourceIds,
            List<ResponsibilityRef> responsibilityIds,
            int laborQuantity,
            String commandId) {
    }

    /** 责任链引用。 */
    private record ResponsibilityRef(String id, int quantity) {
    }

    /** 供测试与调用方判断批次完整性。 */
    public static ContentFingerprint fingerprint(String value) {
        return ContentFingerprint.of(value);
    }

    /** Feed 稳定代码。 */
    public static String feedCode() {
        return FEED_CODE;
    }

    /** 供调用方构造日期（避免测试直接依赖内部类型）。 */
    public static LocalDate businessDateOf(TaskRecord task) {
        return task.workDate();
    }

    /** 便于测试读取映射后的命令键。 */
    public static Map<String, String> batchKeyOf(String commandId) {
        Map<String, String> key = new LinkedHashMap<>();
        key.put("correctionCommandBusinessId", commandId);
        return key;
    }
}

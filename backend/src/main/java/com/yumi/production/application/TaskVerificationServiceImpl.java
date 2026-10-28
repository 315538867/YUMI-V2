package com.yumi.production.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.production.api.DemandType;
import com.yumi.production.api.DefectGroupRequest;
import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.api.TaskItemResultRequest;
import java.math.BigDecimal;
import com.yumi.production.api.TaskStatus;
import com.yumi.production.api.TaskType;
import com.yumi.production.api.TaskVerificationService;
import com.yumi.production.api.VerificationView;
import com.yumi.production.api.VerifyOtherTaskCommand;
import com.yumi.production.api.VerifyTaskCommand;
import com.yumi.production.application.port.AssessmentRepository;
import com.yumi.production.application.port.DailyLoadRepository;
import com.yumi.production.application.port.DemandRecord;
import com.yumi.production.application.port.OtherTaskRepository;
import com.yumi.production.application.port.ProductionRepository;
import com.yumi.production.application.port.RepairSourceRepository;
import com.yumi.production.application.port.SourceReservationRepository;
import com.yumi.production.application.port.TaskItemRecord;
import com.yumi.production.application.port.TaskRecord;
import com.yumi.production.application.port.TaskRepository;
import com.yumi.production.application.port.VerificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 生产核验实现（基线 11 §7.4—§7.7）。
 *
 * <p>数量方程在每条明细上强制成立：计划 = 未完成 + 完成；完成 = 合格 + 返工 + 线上报废；
 * 报废 = 销毁 + 转线下；缺陷事实合计 = 返工 + 报废。未完成数量只释放占用，不生成缺陷、责任或缺口。
 */
public class TaskVerificationServiceImpl implements TaskVerificationService {

    private static final String VERIFIED = "VERIFIED";
    private static final String PENDING = "PENDING";

    private final TaskRepository tasks;
    private final ProductionRepository production;
    private final SourceReservationRepository sourceReservations;
    private final RepairSourceRepository repairSources;
    private final VerificationRepository verifications;
    private final OtherTaskRepository otherTasks;
    private final AssessmentRepository assessments;
    private final DailyLoadService dailyLoads;
    private final NewProductCapacityService capacity;
    private final AuthorizationService authorization;
    private final CurrentActorProvider currentActor;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public TaskVerificationServiceImpl(
            TaskRepository tasks,
            ProductionRepository production,
            SourceReservationRepository sourceReservations,
            RepairSourceRepository repairSources,
            VerificationRepository verifications,
            OtherTaskRepository otherTasks,
            AssessmentRepository assessments,
            DailyLoadService dailyLoads,
            NewProductCapacityService capacity,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.tasks = tasks;
        this.production = production;
        this.sourceReservations = sourceReservations;
        this.repairSources = repairSources;
        this.verifications = verifications;
        this.otherTasks = otherTasks;
        this.assessments = assessments;
        this.dailyLoads = dailyLoads;
        this.capacity = capacity;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public VerificationView verify(VerifyTaskCommand command) {
        authorization.require(PermissionCodes.PRODUCTION_VERIFY, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_VERIFICATION", "核验命令不能为空");
        }
        return transactionTemplate.execute(status -> doVerify(command));
    }

    @Override
    public VerificationView verifyOtherTask(VerifyOtherTaskCommand command) {
        authorization.require(PermissionCodes.PRODUCTION_VERIFY, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_VERIFICATION", "核验命令不能为空");
        }
        return transactionTemplate.execute(status -> doVerifyOtherTask(command));
    }

    private VerificationView doVerify(VerifyTaskCommand command) {
        Instant now = clock.instant();
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();

        List<TaskItemResultRequest> results = command.items();
        String taskBusinessId = resolveTaskBusinessId(results.getFirst());
        TaskRecord task = tasks.lockTask(taskBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_TASK_NOT_FOUND", "任务不存在"));
        if (task.taskType() == TaskType.OTHER_TASK) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_TASK_TYPE_INVALID",
                    "其他任务必须使用其他任务核验命令");
        }
        if (task.status() == TaskStatus.CANCELLED) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_TASK_CANCELLED", "任务已取消，不能核验");
        }

        List<TaskItemRecord> items = tasks.findTaskItems(taskBusinessId);
        Map<String, TaskItemRecord> byId = new LinkedHashMap<>();
        items.forEach(item -> byId.put(item.taskItemBusinessId(), item));

        Set<String> seen = new LinkedHashSet<>();
        List<VerificationView.ItemVerificationView> verified = new ArrayList<>();
        Map<String, Integer> makingPlannedByProduct = new LinkedHashMap<>();
        Map<String, Integer> makingCompletedByProduct = new LinkedHashMap<>();
        Map<String, int[]> demandDeltas = new LinkedHashMap<>();
        long plannedSecondsTotal = 0L;
        long completedSecondsTotal = 0L;
        long totalPlannedSeconds = items.stream()
                .mapToLong(item -> (long) item.plannedQuantity() * item.itemStandardMinutes() * 60L)
                .sum();
        long previouslyVerifiedSeconds = items.stream()
                .filter(item -> VERIFIED.equals(item.verificationStatus()))
                .mapToLong(item -> (long) item.plannedQuantity() * item.itemStandardMinutes() * 60L)
                .sum();
        Set<String> participants = new LinkedHashSet<>();

        for (TaskItemResultRequest request : results) {
            TaskItemRecord item = byId.get(request.taskItemBusinessId());
            if (item == null) {
                throw new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_TASK_ITEM_NOT_FOUND",
                        "任务明细不属于该任务：" + request.taskItemBusinessId());
            }
            if (!seen.add(item.taskItemBusinessId())) {
                throw new ApiException(
                        ApiErrorType.VALIDATION, "PRODUCTION_TASK_ITEM_DUPLICATED", "同一明细不能重复提交");
            }
            if (VERIFIED.equals(item.verificationStatus())) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_ITEM_ALREADY_VERIFIED",
                        "该明细已提交过最终核验，不能再核验");
            }
            if ("CANCELLED".equals(item.verificationStatus())) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_TASK_ITEM_CANCELLED",
                        "该明细已在任务调整中取消，不能再核验");
            }
            requireQuantityEquation(item, request);
            requireDefects(item, request);

            verified.add(writeItemFacts(task, item, request, now, operatorId, makingCompletedByProduct,
                    demandDeltas));
            if (task.processCode() == TargetProcess.MAKING
                    && item.demandType() == DemandType.NEW_PRODUCT
                    && item.repairSourceBusinessId() == null) {
                makingPlannedByProduct.merge(item.productBusinessId(), item.plannedQuantity(), Integer::sum);
            }
            plannedSecondsTotal += (long) item.plannedQuantity() * item.itemStandardMinutes() * 60L;
            completedSecondsTotal += (long) request.completedQuantity() * item.itemStandardMinutes() * 60L;
        }

        applyDemandProjections(demandDeltas);
        applyPhysicalProjection(task, items, verified, now);

        // 来源占用：未完成与完成都要释放本次明细的全部预留（未完成回到可安排数量）
        for (TaskItemResultRequest request : results) {
            TaskItemRecord item = byId.get(request.taskItemBusinessId());
            if (item.repairSourceBusinessId() != null) {
                repairSources.complete(
                        item.repairSourceBusinessId(), item.plannedQuantity(),
                        request.completedQuantity());
                repairSources.recordFact(
                        item.repairSourceBusinessId(), "COMPLETED",
                        request.completedQuantity(), taskBusinessId, item.taskItemBusinessId(),
                        now, operatorId);
            } else {
                sourceReservations.release(
                        item.productionSourceBusinessId(),
                        Math.min(item.plannedQuantity(),
                                reservedOf(item.productionSourceBusinessId())));
            }
            sourceReservations.releaseHold(item.taskItemBusinessId());
        }

        applyLoadAndCapacity(task, makingPlannedByProduct, makingCompletedByProduct,
                plannedSecondsTotal, completedSecondsTotal,
                totalPlannedSeconds, previouslyVerifiedSeconds);

        boolean allVerified = tasks.findTaskItems(taskBusinessId).stream()
                .filter(item -> !"CANCELLED".equals(item.verificationStatus()))
                .allMatch(item -> VERIFIED.equals(item.verificationStatus()));
        TaskStatus nextStatus = allVerified ? TaskStatus.VERIFIED : TaskStatus.PARTIALLY_VERIFIED;
        verifications.updateTaskStatus(taskBusinessId, nextStatus, task.rowVersion());

        return new VerificationView(taskBusinessId, nextStatus, verified, null);
    }

    private VerificationView doVerifyOtherTask(VerifyOtherTaskCommand command) {
        Instant now = clock.instant();
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
        TaskRecord task = tasks.lockTask(command.taskBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_TASK_NOT_FOUND", "任务不存在"));
        if (task.taskType() != TaskType.OTHER_TASK) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_TASK_TYPE_INVALID",
                    "只有其他任务可以使用本命令核验");
        }
        if (verifications.findOtherTaskVerification(task.taskBusinessId()).isPresent()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_ITEM_ALREADY_VERIFIED",
                    "该其他任务已核验，不能重复核验");
        }
        var detail = otherTasks.find(task.taskBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_OTHER_TASK_NOT_FOUND", "其他任务详情不存在"));

        Integer planned = detail.plannedQuantity();
        Integer completed = command.completedQuantity();
        Integer unfinished = command.unfinishedQuantity();
        if (planned != null) {
            if (completed == null || unfinished == null) {
                throw new ApiException(
                        ApiErrorType.VALIDATION, "PRODUCTION_QUANTITY_INVALID",
                        "关联商品的其他任务必须给出完成与未完成数量");
            }
            if (planned != completed + unfinished) {
                throw new ApiException(
                        ApiErrorType.VALIDATION, "PRODUCTION_QUANTITY_MISMATCH",
                        "计划数量必须等于完成与未完成数量之和");
            }
        } else if (completed != null || unfinished != null) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCTION_QUANTITY_INVALID",
                    "未关联商品的其他任务不填写数量");
        }

        String factBusinessId = "OTV-" + UUID.randomUUID();
        verifications.insertOtherTaskVerification(new VerificationRepository.OtherTaskVerification(
                factBusinessId, task.taskBusinessId(), task.activeAssignmentVersion(),
                task.assessmentBusinessId(), planned, completed, unfinished,
                command.resultNote(), now, task.workDate(), operatorId));

        // 其他任务不产生制品数量、阶段或材料成本来源；关联商品时只生成劳动来源
        String laborSourceId = null;
        if (planned != null && completed != null && completed > 0) {
            laborSourceId = "LS-" + UUID.randomUUID();
            verifications.insertLaborSource(new VerificationRepository.LaborSource(
                    laborSourceId, task.taskBusinessId(), null, null, null,
                    task.subject().subjectType().name(), task.subject().subjectBusinessId(),
                    task.taskType().name(), null, completed, detail.frozenUnitLaborAmount() == null
                            ? null : detail.frozenUnitLaborAmount().value(),
                    task.workDate(), now, "APPLY"));
        }

        // 整体标准负载由计划转为已核验；不宣称它是真实耗时
        for (String employeeId : participantsOf(task)) {
            dailyLoads.movePlanToCommitted(
                    employeeId, task.workDate(), task.taskStandardSeconds(), task.taskStandardSeconds());
        }
        verifications.updateTaskStatus(task.taskBusinessId(), TaskStatus.VERIFIED, task.rowVersion());

        return new VerificationView(task.taskBusinessId(), TaskStatus.VERIFIED, List.of(),
                new VerificationView.OtherTaskVerificationView(
                        factBusinessId, task.workDate(), planned, completed, unfinished,
                        command.resultNote(), laborSourceId));
    }

    private String resolveTaskBusinessId(TaskItemResultRequest request) {
        return tasks.findTaskItem(request.taskItemBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_TASK_ITEM_NOT_FOUND", "任务明细不存在"))
                .taskBusinessId();
    }

    private static void requireQuantityEquation(TaskItemRecord item, TaskItemResultRequest request) {
        if (request.completedQuantity() + request.unfinishedQuantity() != item.plannedQuantity()) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCTION_QUANTITY_MISMATCH",
                    "计划数量必须等于未完成与完成数量之和：计划 " + item.plannedQuantity()
                            + "，未完成 " + request.unfinishedQuantity() + "，完成 "
                            + request.completedQuantity());
        }
        if (request.qualifiedQuantity() + request.reworkQuantity() + request.scrappedQuantity()
                != request.completedQuantity()) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCTION_QUANTITY_MISMATCH",
                    "完成数量必须等于合格、返工与报废数量之和");
        }
    }

    private void requireDefects(TaskItemRecord item, TaskItemResultRequest request) {
        int defectTotal = request.defects().stream().mapToInt(DefectGroupRequest::quantity).sum();
        int reworkTotal = request.defects().stream().mapToInt(DefectGroupRequest::reworkQuantity).sum();
        int scrapTotal = request.defects().stream().mapToInt(DefectGroupRequest::scrappedQuantity).sum();
        if (defectTotal != request.reworkQuantity() + request.scrappedQuantity()) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCTION_QUANTITY_MISMATCH",
                    "缺陷数量合计必须等于返工与线上报废之和");
        }
        if (reworkTotal != request.reworkQuantity() || scrapTotal != request.scrappedQuantity()) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCTION_QUANTITY_MISMATCH",
                    "按原因分组的返工与报废数量必须与明细合计一致");
        }
        Set<String> reasons = new LinkedHashSet<>();
        for (DefectGroupRequest defect : request.defects()) {
            if (!reasons.add(defect.defectReasonCode())) {
                throw new ApiException(
                        ApiErrorType.VALIDATION, "PRODUCTION_DEFECT_INVALID",
                        "同一明细内缺陷原因不能重复，请合并数量：" + defect.defectReasonCode());
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
            if ((reason.other() || reason.requiresText())
                    && (defect.defectReasonText() == null
                    || defect.defectReasonText().isBlank())) {
                throw new ApiException(
                        ApiErrorType.VALIDATION, "PRODUCTION_DEFECT_INVALID",
                        "选择“其他”缺陷原因必须填写说明");
            }
        }
    }

    private VerificationView.ItemVerificationView writeItemFacts(
            TaskRecord task, TaskItemRecord item, TaskItemResultRequest request, Instant now,
            String operatorId, Map<String, Integer> makingByProduct, Map<String, int[]> demandDeltas) {
        DemandRecord demand = production.findDemand(item.demandBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_DEMAND_NOT_FOUND", "生产需求不存在"));

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
                    "QLF-" + UUID.randomUUID(), item.taskItemBusinessId(), "UNQUALIFIED",
                    unqualified, now));
        }

        List<VerificationView.DefectView> defectViews = new ArrayList<>();
        List<String> repairSourceIds = new ArrayList<>();
        for (DefectGroupRequest defect : request.defects()) {
            var reason = verifications.findDefectReason(defect.defectReasonCode()).orElseThrow();
            String defectFactId = "DFF-" + UUID.randomUUID();
            verifications.insertDefect(new VerificationRepository.DefectFact(
                    defectFactId, item.taskItemBusinessId(), reason.code(), reason.name(),
                    defect.defectReasonText(), defect.quantity(), now));

            List<VerificationView.ScrapView> scrapViews = new ArrayList<>();
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
                if (part.physicalDestination() == PhysicalDestination.TRANSFERRED_OFFLINE_INVENTORY) {
                    verifications.insertOfflineTransfer(new VerificationRepository.OfflineTransferRecord(
                            "TRF-" + UUID.randomUUID(), exitId, item.productionSourceBusinessId(),
                            item.physicalRootSourceBusinessId(), demand.demandBusinessId(),
                            demand.productBusinessId(), "QUALITY_SCRAP",
                            part.offlineInventoryName().strip(), part.offlineInventoryNote(),
                            part.handedOverBy().strip(), part.receivedBy().strip(),
                            part.transferredAt().atStartOfDay().toInstant(ZoneOffset.UTC),
                            part.quantity(), now, operatorId));
                }
                String dispositionId = "DSF-" + UUID.randomUUID();
                verifications.insertDisposition(new VerificationRepository.DispositionFact(
                        dispositionId, defectFactId, item.taskItemBusinessId(), "SCRAPPED",
                        part.physicalDestination(), part.quantity(), exitId, null, now));
                String responsibilityId = "RSP-" + UUID.randomUUID();
                verifications.insertResponsibility(new VerificationRepository.ResponsibilityFact(
                        responsibilityId, dispositionId, exitId,
                        part.responsibilityType().name(), part.responsibleSubjectBusinessId(),
                        part.waivedReason(), part.quantity(), task.workDate(), now));
                verifications.insertResponsibilityRevision(
                        "RVN-" + UUID.randomUUID(), responsibilityId, dispositionId,
                        part.responsibilityType().name(), part.responsibleSubjectBusinessId(),
                        part.waivedReason(), part.quantity(), task.workDate(),
                        "验收报废责任", now, operatorId);
                scrapViews.add(new VerificationView.ScrapView(
                        part.quantity(), part.physicalDestination(), exitId,
                        part.responsibilityType(), part.responsibleSubjectBusinessId(),
                        part.waivedReason()));
            }
            defectViews.add(new VerificationView.DefectView(
                    defectFactId, reason.code(), defect.quantity(), defect.reworkQuantity(),
                    defect.scrappedQuantity(), scrapViews));
        }

        verifications.insertItemVerification(new VerificationRepository.ItemVerificationRecord(
                item.taskItemBusinessId(), task.taskBusinessId(), item.assignmentVersion(),
                item.plannedQuantity(), request.unfinishedQuantity(), request.completedQuantity(),
                request.qualifiedQuantity(), request.reworkQuantity(), request.scrappedQuantity(),
                request.destroyedQuantity(), request.scrapOfflineQuantity(), task.workDate(), now,
                operatorId, request.resultNote()));
        verifications.updateTaskItemVerification(
                item.taskItemBusinessId(), request.completedQuantity(), request.unfinishedQuantity(),
                request.qualifiedQuantity(), request.reworkQuantity(), request.scrappedQuantity(),
                request.destroyedQuantity(), request.scrapOfflineQuantity(), VERIFIED);

        if (task.processCode() == TargetProcess.MAKING
                && demand.demandType() == DemandType.NEW_PRODUCT
                && item.repairSourceBusinessId() == null) {
            makingByProduct.merge(item.productBusinessId(), request.completedQuantity(), Integer::sum);
        }
        demandDeltas.computeIfAbsent(demand.demandBusinessId(), key -> new int[5]);
        accumulateDemand(
                demandDeltas.get(demand.demandBusinessId()), demand, task.processCode(), request,
                item.repairSourceBusinessId() != null);

        // 材料成本来源：仅真实生产/修复任务，按依据中的材料行逐项写入，不含金额
        if (request.completedQuantity() > 0) {
            for (String component : materialComponents(demand)) {
                verifications.insertMaterialCostSource(new VerificationRepository.MaterialCostSource(
                        "MCS-" + UUID.randomUUID(), task.taskBusinessId(), item.taskItemBusinessId(),
                        demand.demandBusinessId(), item.productionSourceBusinessId(),
                        item.physicalRootSourceBusinessId(), item.productBusinessId(),
                        item.productSnapshotRef(), item.schemeBusinessId(), item.schemeSnapshotRef(),
                        component, task.processCode().name(), request.completedQuantity(),
                        task.workDate(), now, "APPLY"));
            }
            // 计件劳动来源：按本工序历史人工标准与完成数量发布，不把参考当实际工资
            BigDecimal unitLabor = laborUnitAmount(demand, task.processCode());
            verifications.insertLaborSource(new VerificationRepository.LaborSource(
                    "LS-" + UUID.randomUUID(), task.taskBusinessId(), item.taskItemBusinessId(),
                    demand.demandBusinessId(), item.productionSourceBusinessId(),
                    task.subject().subjectType().name(), task.subject().subjectBusinessId(),
                    task.taskType().name(), task.processCode().name(), request.completedQuantity(),
                    unitLabor, task.workDate(), now, "APPLY"));
        }

        return new VerificationView.ItemVerificationView(
                item.taskItemBusinessId(), demand.demandBusinessId(), item.productionSourceBusinessId(),
                item.plannedQuantity(), request.unfinishedQuantity(), request.completedQuantity(),
                request.qualifiedQuantity(), request.reworkQuantity(), request.scrappedQuantity(),
                request.destroyedQuantity(), request.scrapOfflineQuantity(), defectViews, repairSourceIds);
    }

    /** 需求投影增量：索引 0 未制、1 在制、2 目标就绪、3 缺口。 */
    private static void accumulateDemand(
            int[] delta, DemandRecord demand, TargetProcess process, TaskItemResultRequest request,
            boolean repair) {
        int completed = request.completedQuantity();
        boolean atTarget = process == demand.targetProcess();
        boolean atMaking = process == TargetProcess.MAKING;

        if (repair) {
            // 返工只移动原制品：完成数量先退出在制，合格回到目标就绪或在制，
            // 返工继续在制并生成新返工来源，报废退出在制并形成缺口。
            delta[1] -= completed;
            if (atTarget) {
                delta[2] += request.qualifiedQuantity();
            } else {
                delta[1] += request.qualifiedQuantity();
            }
            delta[1] += request.reworkQuantity();
            delta[3] += request.scrappedQuantity();
            return;
        }
        if (atMaking) {
            delta[0] -= completed;
        } else {
            delta[1] -= completed;
        }
        if (atTarget) {
            delta[2] += request.qualifiedQuantity();
        } else {
            delta[1] += request.qualifiedQuantity();
        }
        // 返工/修补制品仍在系统内，计入在制；报废形成履约缺口
        delta[1] += request.reworkQuantity();
        delta[3] += request.scrappedQuantity();
    }

    private void applyDemandProjections(Map<String, int[]> demandDeltas) {
        for (var entry : demandDeltas.entrySet()) {
            var current = production.findDemandProjection(entry.getKey())
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_PROJECTION_MISSING", "需求数量投影缺失"));
            int[] delta = entry.getValue();
            int pending = current.pendingWorkQuantity() + delta[0];
            int inProcess = current.inProcessQuantity() + delta[1];
            int targetReady = current.targetReadyQuantity() + delta[2];
            int gap = current.fulfillmentGapQuantity() + delta[3];
            if (pending < 0 || inProcess < 0 || targetReady < 0 || gap < 0) {
                throw new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_PROJECTION_INCONSISTENT",
                        "数量投影出现负数：pending=" + pending + ", inProcess=" + inProcess
                                + ", targetReady=" + targetReady + ", gap=" + gap);
            }
            verifications.updateDemandProjection(
                    entry.getKey(), pending, inProcess, targetReady, gap);
        }
    }

    /** 物理制品投影：累计制作量只在 MAKING 合格后增加；报废按去向退出。 */
    private void applyPhysicalProjection(
            TaskRecord task, List<TaskItemRecord> items,
            List<VerificationView.ItemVerificationView> verified, Instant now) {
        Map<String, int[]> deltas = new LinkedHashMap<>();
        for (VerificationView.ItemVerificationView view : verified) {
            TaskItemRecord item = items.stream()
                    .filter(candidate -> candidate.taskItemBusinessId().equals(view.taskItemBusinessId()))
                    .findFirst()
                    .orElseThrow();
            boolean atMaking = task.processCode() == TargetProcess.MAKING
                    && item.demandType() == DemandType.NEW_PRODUCT
                    && item.repairSourceBusinessId() == null;
            int[] delta = deltas.computeIfAbsent(item.physicalRootSourceBusinessId(), key -> new int[5]);
            if (atMaking) {
                // 制作完成即产生制品：合格与返工仍在系统内，报废按去向退出
                delta[0] += view.completedQuantity();
                delta[1] += view.qualifiedQuantity() + view.reworkQuantity();
            } else {
                delta[1] -= view.destroyedQuantity() + view.scrapOfflineQuantity();
            }
            delta[3] += view.destroyedQuantity();
            delta[4] += view.scrapOfflineQuantity();
        }
        for (var entry : deltas.entrySet()) {
            var current = production.findPhysicalProjection(entry.getKey())
                    .orElseGet(() -> new com.yumi.production.application.port.QuantityProjections
                            .PhysicalProjection(entry.getKey(), 0, 0, 0, 0, 0, 1L));
            int[] delta = entry.getValue();
            var updated = new com.yumi.production.application.port.QuantityProjections.PhysicalProjection(
                    entry.getKey(),
                    current.cumulativeMakingCreatedQuantity() + delta[0],
                    current.inSystemQuantity() + delta[1],
                    current.consumedQuantity() + delta[2],
                    current.destroyedQuantity() + delta[3],
                    current.offlineInventoryQuantity() + delta[4],
                    current.rowVersion() + 1);
            production.insertPhysicalProjection(updated);
        }
    }

    /**
     * 负载与产能结账。
     *
     * <p>任务标准秒数已由计算中心按小组效率取整保存；这里以“完成基准秒数 / 全部基准秒数”的比例
     * 把它分配到完成部分，避免用当前效率重算历史。
     */
    private void applyLoadAndCapacity(
            TaskRecord task, Map<String, Integer> makingPlannedByProduct,
            Map<String, Integer> makingCompletedByProduct, long plannedBaseSeconds,
            long completedBaseSeconds, long totalPlannedBaseSeconds,
            long previouslyVerifiedBaseSeconds) {
        // 任务总负载按明细基准秒数分摊；本次只移走被核验明细应占的份额
        long plannedLoad = totalPlannedBaseSeconds == 0
                ? 0L
                : Math.round((double) task.taskStandardSeconds()
                        * (previouslyVerifiedBaseSeconds + plannedBaseSeconds)
                        / totalPlannedBaseSeconds)
                        - Math.round((double) task.taskStandardSeconds()
                                * previouslyVerifiedBaseSeconds / totalPlannedBaseSeconds);
        long completedLoad = totalPlannedBaseSeconds == 0
                ? 0L
                : Math.round((double) task.taskStandardSeconds()
                        * completedBaseSeconds / totalPlannedBaseSeconds);
        for (String employeeId : participantsOf(task)) {
            dailyLoads.movePlanToCommitted(employeeId, task.workDate(), plannedLoad, completedLoad);
        }
        for (var entry : makingPlannedByProduct.entrySet()) {
            var lock = capacity.lockKeepingLimit(entry.getKey(), task.workDate());
            capacity.movePlanToCommitted(
                    lock, entry.getValue(), makingCompletedByProduct.getOrDefault(entry.getKey(), 0));
        }
    }

    private int reservedOf(String productionSourceBusinessId) {
        return sourceReservations.lockReservedQuantity(productionSourceBusinessId);
    }

    /** 参与人来自活动引用表；小组任务记录全部快照成员。 */
    private Set<String> participantsOf(TaskRecord task) {
        Set<String> participants = new LinkedHashSet<>(tasks.findParticipantEmployeeIds(task.taskBusinessId()));
        if (participants.isEmpty()) {
            participants.add(task.subject().subjectBusinessId());
        }
        return participants;
    }

    /** 本工序历史单件人工标准合计；缺少行时返回 null，由工资参考标记不完整。 */
    private BigDecimal laborUnitAmount(DemandRecord demand, TargetProcess process) {
        try {
            JsonNode basis = objectMapper.readTree(demand.productionBasisJson());
            BigDecimal total = null;
            for (JsonNode line : basis.path("costLines")) {
                if (!"LABOR".equals(line.path("nature").asText(""))) {
                    continue;
                }
                if (!process.name().equals(line.path("processCode").asText(""))) {
                    continue;
                }
                BigDecimal amount = new BigDecimal(line.path("fullResourceCost").asText("0"));
                total = total == null ? amount : total.add(amount);
            }
            return total;
        } catch (Exception exception) {
            return null;
        }
    }

    private List<String> materialComponents(DemandRecord demand) {
        try {
            JsonNode basis = objectMapper.readTree(demand.productionBasisJson());
            List<String> components = new ArrayList<>();
            for (JsonNode line : basis.path("costLines")) {
                String nature = line.path("nature").asText("");
                if ("NON_LABOR_MATERIAL".equals(nature)) {
                    components.add(line.path("componentCode").asText());
                }
            }
            return components;
        } catch (Exception exception) {
            return List.of();
        }
    }
}

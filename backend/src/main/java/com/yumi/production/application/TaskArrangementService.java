package com.yumi.production.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.calculation.api.WorkloadComparison;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.numbering.BusinessNumberService;
import com.yumi.product.api.ProductSnapshotRef;
import com.yumi.product.api.ProductSnapshotService;
import com.yumi.production.api.ArrangeTaskCommand;
import com.yumi.production.api.CancelTaskCommand;
import com.yumi.production.api.DemandType;
import com.yumi.production.api.SubjectType;
import com.yumi.production.api.SubjectTaskSlot;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.api.TaskApplicationService;
import com.yumi.production.api.TaskItemRequest;
import com.yumi.production.api.TaskRef;
import com.yumi.production.api.TaskStatus;
import com.yumi.production.api.TaskType;
import com.yumi.production.api.TaskView;
import com.yumi.production.application.port.AssessmentRepository;
import com.yumi.production.application.port.DailyLoadRepository;
import com.yumi.production.application.port.DemandRecord;
import com.yumi.production.application.port.OtherTaskRepository;
import com.yumi.production.application.port.ProductionRepository;
import com.yumi.production.application.port.SourceRecord;
import com.yumi.production.application.port.ReductionRepository;
import com.yumi.production.application.port.SourceReservationRepository;
import com.yumi.production.application.port.TaskItemRecord;
import com.yumi.production.application.port.TaskRecord;
import com.yumi.production.application.port.TaskRepository;
import com.yumi.workforce.api.AssignmentSubjectRef;
import com.yumi.workforce.api.AssignmentSubjectSnapshotBundle;
import com.yumi.workforce.api.AssignmentSubjectSnapshotService;
import com.yumi.workforce.api.AssignmentSubjectSnapshotBundle.StandardSnapshotRef;
import com.yumi.workforce.api.AssignmentSubjectSnapshotView;
import com.yumi.workforce.api.AssignmentSubjectType;
import com.yumi.workforce.api.PreparedActivityReservation;
import com.yumi.workforce.api.ReserveWorkforceActivityCommand;
import com.yumi.workforce.api.ReleaseWorkforceActivityCommand;
import com.yumi.workforce.api.WorkforceActivityReservationParticipant;
import com.yumi.workforce.api.WorkforceActivitySourceRef;
import com.yumi.workforce.api.WorkforceActivitySourceRef;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
 * 任务安排实现。
 *
 * <p>规则来源：`docs/一期/11-生产作业模块.md` §6、§7.1、§6.7：
 * <ul>
 *   <li>任务计划总数量 = 明细计划数量之和；每条明细只对应一个生产来源；</li>
 *   <li>来源可安排数量不足即拒绝，不用 {@code max(0)} 截断；</li>
 *   <li>同员工同日：正常/超额/其他各最多一个，超额与返工依赖同日有效正常任务；</li>
 *   <li>{@code OVER_STANDARD} 仅超额任务放行，其余类型需要覆盖原因；{@code UNAVAILABLE} 一律拒绝；</li>
 *   <li>只有 {@code NEW_PRODUCT + MAKING} 明细占用商品新制产能；</li>
 *   <li>其他任务不关联生产来源，只按整体标准分钟预留人员负载、活动与日期槽位。</li>
 * </ul>
 */
public class TaskArrangementService implements TaskApplicationService {

    private static final String SOURCE_MODULE = "production";
    private static final String SOURCE_TYPE = "TASK";
    private static final String WORK_MODE_PRODUCTION = "PRODUCTION";
    private static final String WORK_MODE_OTHER = "OTHER_TASK";
    private static final String SLOT_ACTIVE = "ACTIVE";
    private static final String SLOT_RELEASED = "RELEASED";
    private static final String OPENING_PENDING = "PENDING_OPENING";

    private final TaskRepository tasks;
    private final ProductionRepository production;
    private final SourceReservationRepository sourceReservations;
    private final ReductionRepository reductions;
    private final OtherTaskRepository otherTasks;
    private final AssessmentRepository assessments;
    private final DailyLoadRepository dailyLoads;
    private final DailyLoadService dailyLoadService;
    private final NewProductCapacityService capacityService;
    private final ProductSnapshotService productSnapshots;
    private final AssignmentSubjectSnapshotService subjectSnapshots;
    private final WorkforceActivityReservationParticipant workforce;
    private final WorkloadAssessor workloadAssessor;
    private final BusinessNumberService numbering;
    private final AuthorizationService authorization;
    private final CurrentActorProvider currentActor;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public TaskArrangementService(
            TaskRepository tasks,
            ProductionRepository production,
            SourceReservationRepository sourceReservations,
            ReductionRepository reductions,
            OtherTaskRepository otherTasks,
            AssessmentRepository assessments,
            DailyLoadRepository dailyLoads,
            DailyLoadService dailyLoadService,
            NewProductCapacityService capacityService,
            ProductSnapshotService productSnapshots,
            AssignmentSubjectSnapshotService subjectSnapshots,
            WorkforceActivityReservationParticipant workforce,
            WorkloadAssessor workloadAssessor,
            BusinessNumberService numbering,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.tasks = tasks;
        this.production = production;
        this.sourceReservations = sourceReservations;
        this.reductions = reductions;
        this.otherTasks = otherTasks;
        this.assessments = assessments;
        this.dailyLoads = dailyLoads;
        this.dailyLoadService = dailyLoadService;
        this.capacityService = capacityService;
        this.productSnapshots = productSnapshots;
        this.subjectSnapshots = subjectSnapshots;
        this.workforce = workforce;
        this.workloadAssessor = workloadAssessor;
        this.numbering = numbering;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public TaskRef arrange(ArrangeTaskCommand command) {
        authorization.require(PermissionCodes.PRODUCTION_SCHEDULE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_TASK", "任务命令不能为空");
        }
        return transactionTemplate.execute(status -> doArrange(command));
    }

    @Override
    public TaskView getTask(String taskBusinessId) {
        authorization.require(PermissionCodes.PRODUCTION_READ, ResourceScope.GLOBAL);
        TaskRecord task = tasks.findTask(taskBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_TASK_NOT_FOUND", "任务不存在"));
        return viewOf(task);
    }

    @Override
    public TaskRef cancel(CancelTaskCommand command) {
        authorization.require(PermissionCodes.PRODUCTION_SCHEDULE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_CANCEL", "任务取消命令不能为空");
        }
        return transactionTemplate.execute(status -> doCancel(command));
    }

    private TaskRef doCancel(CancelTaskCommand command) {
        TaskRecord task = tasks.lockTask(command.taskBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_TASK_NOT_FOUND", "任务不存在"));
        if (task.status() == TaskStatus.CANCELLED) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "TASK_ALREADY_CANCELLED", "任务已取消");
        }
        List<TaskItemRecord> items = tasks.findTaskItems(task.taskBusinessId());
        List<TaskItemRecord> pending = items.stream()
                .filter(item -> "PENDING".equals(item.verificationStatus()))
                .toList();
        boolean otherTaskPending = items.isEmpty()
                && task.taskType() == TaskType.OTHER_TASK
                && task.status() == TaskStatus.ARRANGED;
        if (pending.isEmpty() && !otherTaskPending) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "TASK_CANCEL_NO_PENDING",
                    "任务没有可取消的未核验明细");
        }
        boolean hasVerified = items.stream()
                .anyMatch(item -> !"PENDING".equals(item.verificationStatus()));
        List<String> participants = tasks.findParticipantEmployeeIds(task.taskBusinessId());

        var preparedRelease = workforce.prepareRelease(
                new ReleaseWorkforceActivityCommand(new WorkforceActivitySourceRef(
                        SOURCE_MODULE, SOURCE_TYPE, task.taskBusinessId())));
        dailyLoadService.lockDays(participants, task.workDate());

        Map<String, Integer> makingByProduct = new LinkedHashMap<>();
        if (task.processCode() == TargetProcess.MAKING) {
            for (TaskItemRecord item : pending) {
                if (item.demandType() == com.yumi.production.api.DemandType.NEW_PRODUCT) {
                    makingByProduct.merge(
                            item.productBusinessId(), item.plannedQuantity(), Integer::sum);
                }
            }
        }
        Map<String, NewProductCapacityService.CapacityLock> capacityLocks =
                makingByProduct.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> capacityService.lockKeepingLimit(
                                entry.getKey(), task.workDate()),
                        (first, second) -> first, LinkedHashMap::new));

        pending.stream().map(TaskItemRecord::productionSourceBusinessId).distinct().sorted()
                .forEach(sourceReservations::lockReservedQuantity);

        if (otherTaskPending) {
            for (String employeeId : participants) {
                dailyLoadService.releasePlan(
                        employeeId, task.workDate(), task.taskStandardSeconds());
            }
        }
        for (TaskItemRecord item : pending) {
            reductions.updateItemPlannedQuantity(
                    item.taskItemBusinessId(), 0, item.plannedQuantity());
            sourceReservations.releaseHold(item.taskItemBusinessId());
            sourceReservations.release(
                    item.productionSourceBusinessId(), item.plannedQuantity());
            long seconds = (long) item.plannedQuantity() * item.itemStandardMinutes() * 60L;
            for (String employeeId : participants) {
                dailyLoadService.releasePlan(employeeId, task.workDate(), seconds);
            }
            if (task.processCode() == TargetProcess.MAKING
                    && item.demandType() == com.yumi.production.api.DemandType.NEW_PRODUCT) {
                capacityService.releasePlan(
                        capacityLocks.get(item.productBusinessId()), item.plannedQuantity());
            }
        }
        workforce.commitRelease(preparedRelease);

        String slotType = task.taskType() == TaskType.REPAIR
                ? SubjectTaskSlot.REPAIR.name() : task.taskType().name();
        if (hasVerified) {
            int remainingPlanned = items.stream()
                    .filter(item -> !"PENDING".equals(item.verificationStatus()))
                    .mapToInt(TaskItemRecord::plannedQuantity).sum();
            long remainingSeconds = items.stream()
                    .filter(item -> !"PENDING".equals(item.verificationStatus()))
                    .mapToLong(item -> (long) item.plannedQuantity() * item.itemStandardMinutes() * 60L)
                    .sum();
            reductions.updateTaskTotals(
                    task.taskBusinessId(), remainingPlanned, remainingSeconds);
            tasks.updateTaskStatus(task.taskBusinessId(), TaskStatus.PARTIALLY_VERIFIED);
            for (String employeeId : participants) {
                dailyLoads.upsertSlot(
                        employeeId, task.workDate(), slotType, task.taskBusinessId(), "RETAINED");
            }
            return new TaskRef(
                    task.taskBusinessId(), task.taskNo(), task.taskType(),
                    TaskStatus.PARTIALLY_VERIFIED, task.processCode(), remainingPlanned,
                    remainingSeconds, task.activeAssignmentVersion(), task.rowVersion() + 1);
        }
        reductions.cancelTask(task.taskBusinessId());
        for (String employeeId : participants) {
            dailyLoads.upsertSlot(
                    employeeId, task.workDate(), slotType, task.taskBusinessId(), SLOT_RELEASED);
            dailyLoads.releaseWorkMode(employeeId, task.workDate());
        }
        return new TaskRef(
                task.taskBusinessId(), task.taskNo(), task.taskType(), TaskStatus.CANCELLED,
                task.processCode(), 0, 0, task.activeAssignmentVersion(), task.rowVersion() + 1);
    }

    @Override
    public List<TaskView> listTasks(java.time.LocalDate workDate) {
        authorization.require(PermissionCodes.PRODUCTION_READ, ResourceScope.GLOBAL);
        if (workDate == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_WORK_DATE", "工作日期不能为空");
        }
        return tasks.findTasksByWorkDate(workDate).stream().map(this::viewOf).toList();
    }

    private TaskView viewOf(TaskRecord task) {
        String taskBusinessId = task.taskBusinessId();
        List<TaskItemRecord> items = tasks.findTaskItems(taskBusinessId);
        var assessment = assessments.findByTask(taskBusinessId, task.activeAssignmentVersion()).orElse(null);
        var otherTask = otherTasks.find(taskBusinessId).orElse(null);
        return new TaskView(
                task.taskBusinessId(), task.taskNo(), task.taskType(), task.status(), task.workDate(),
                task.subject(), task.processCode(), task.plannedWorkQuantity(), task.taskStandardSeconds(),
                items.stream().map(item -> new TaskView.ItemView(
                        item.taskItemBusinessId(), item.itemOrder(), item.demandBusinessId(),
                        item.demandType(), item.productionSourceBusinessId(), item.repairSourceBusinessId(),
                        item.productBusinessId(), item.productSnapshotRef(), item.schemeSnapshotRef(),
                        item.targetProcess(), item.plannedQuantity(), item.itemStandardMinutes(),
                        item.verificationStatus())).toList(),
                assessment == null ? null : new TaskView.AssessmentView(
                        assessment.assessmentBusinessId(), assessment.taskStandardSeconds(),
                        assessment.availableEffectiveSeconds(), assessment.assessmentResult(),
                        assessment.formulaRuleVersion(), assessment.inputFingerprint(),
                        assessment.overrideReason(), List.of()),
                otherTask == null ? null : new TaskView.OtherTaskView(
                        otherTask.workContent(), otherTask.relatedProductBusinessId(),
                        otherTask.plannedQuantity(), otherTask.frozenUnitLaborAmount(),
                        otherTask.resultNote()));
    }

    private TaskRef doArrange(ArrangeTaskCommand command) {
        Instant now = clock.instant();
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
        String taskBusinessId = "TASK-" + UUID.randomUUID();
        String assessmentBusinessId = "ASMT-" + UUID.randomUUID();

        List<PlannedItem> planned = command.taskType().bindsProductionSource()
                ? planBoundItems(command) : List.of();
        OtherTaskRepository.OtherTaskRecord otherTaskRecord = command.taskType() == TaskType.OTHER_TASK
                ? planOtherTask(command, taskBusinessId) : null;

        PreparedActivityReservation prepared = workforce.prepareReserve(
                new ReserveWorkforceActivityCommand(
                        new WorkforceActivitySourceRef(SOURCE_MODULE, SOURCE_TYPE, taskBusinessId),
                        new AssignmentSubjectRef(
                                command.subject().subjectType() == SubjectType.TEAM
                                        ? AssignmentSubjectType.TEAM : AssignmentSubjectType.EMPLOYEE,
                                command.subject().subjectBusinessId()),
                        command.processCode() == null ? null : command.processCode().name(),
                        command.workDate(),
                        List.of()));
        prepared = prepared.withSource(
                new WorkforceActivitySourceRef(SOURCE_MODULE, SOURCE_TYPE, taskBusinessId));
        AssignmentSubjectSnapshotBundle bundle = prepared.bundle();
        Set<String> participants = participantsOf(command, bundle);

        applySlotsAndModes(command, participants);
        Map<String, DailyLoadService.DayLoad> loads =
                dailyLoadService.lockDays(participants, command.workDate());

        Map<String, Integer> makingByProduct = makingQuantityByProduct(command, planned);
        Map<String, NewProductCapacityService.CapacityLock> capacityLocks =
                capacityService.lock(makingByProduct, command.workDate());
        for (var entry : makingByProduct.entrySet()) {
            capacityService.reservePlan(capacityLocks.get(entry.getKey()), entry.getValue());
        }

        for (PlannedItem item : planned) {
            requireSourceAvailable(item);
        }

        WorkloadAssessor.AssignableResult assessed = assess(command, planned, bundle, loads);
        requirePermitted(command.taskType(), assessed.comparison(), command.overrideReason());

        TaskRecord task = new TaskRecord(
                0L, taskBusinessId, numbering.nextNumber("PRODUCTION_TASK", "T", 6),
                command.taskType(), command.workDate(), command.processCode(),
                command.taskType() == TaskType.OTHER_TASK
                        ? (command.otherTask().plannedQuantity() == null
                                ? 0 : command.otherTask().plannedQuantity())
                        : planned.stream().mapToInt(PlannedItem::quantity).sum(),
                command.enteredStandardMinutes(), assessed.requiredSeconds(), command.subject(),
                bundle.subjectSnapshot().snapshotBusinessId(), assessmentBusinessId,
                bundle.dailyWorkTimeSnapshot().snapshotBusinessId(),
                bundle.efficiencyStandardSnapshot() == null
                        ? null : bundle.efficiencyStandardSnapshot().snapshotBusinessId(),
                TaskStatus.ARRANGED, 1L, 1L, now, operatorId, now);
        tasks.insertTask(task);
        if (otherTaskRecord != null) {
            otherTasks.insert(otherTaskRecord);
        }

        for (PlannedItem item : planned) {
            TaskItemRecord record = item.toRecord(taskBusinessId);
            tasks.insertTaskItem(record);
            tasks.insertItemAssignmentFact(record, 1L, "CREATE", now, operatorId);
            sourceReservations.reserve(item.productionSourceBusinessId(), item.quantity());
            sourceReservations.upsertActiveHold(new SourceReservationRepository.SourceHold(
                    taskBusinessId, item.taskItemBusinessId(), item.productionSourceBusinessId(),
                    item.quantity(), "ACTIVE"));
        }
        tasks.insertAssignmentFact(
                taskBusinessId, 1L, "CREATE", bundle.subjectSnapshot().snapshotBusinessId(),
                command.workDate(), command.processCode() == null ? null : command.processCode().name(),
                task.plannedWorkQuantity(), now, operatorId);

        saveAssessment(assessmentBusinessId, taskBusinessId, planned, assessed, bundle,
                participants, command.overrideReason());

        workforce.commitReserve(prepared);
        dailyLoadService.reservePlan(loads, assessed.membersAddedSeconds());
        applySlotWrites(command, participants, taskBusinessId);
        for (String employeeId : participants) {
            dailyLoads.insertActiveParticipant(
                    taskBusinessId, employeeId, bundle.subjectSnapshot().subjectType().name(),
                    bundle.subjectView().ref().subjectBusinessId(), command.workDate(),
                    assessed.membersAddedSeconds());
        }
        dailyLoads.insertActiveSubject(
                taskBusinessId, bundle.subjectSnapshot().subjectType().name(),
                bundle.subjectView().ref().subjectBusinessId(), command.workDate());

        return new TaskRef(
                taskBusinessId, task.taskNo(), task.taskType(), task.status(), task.processCode(),
                task.plannedWorkQuantity(), task.taskStandardSeconds(),
                task.activeAssignmentVersion(), task.rowVersion());
    }

    private OtherTaskRepository.OtherTaskRecord planOtherTask(
            ArrangeTaskCommand command, String taskBusinessId) {
        ArrangeTaskCommand.OtherTaskRequest request = command.otherTask();
        String relatedProduct = request.relatedProductBusinessId();
        com.yumi.platform.api.value.CostAmount unitLabor = null;
        String productSnapshotRef = null;
        if (relatedProduct != null && !relatedProduct.isBlank()) {
            ProductSnapshotRef snapshot = productSnapshots.acquireProductSnapshot(relatedProduct);
            var view = productSnapshots.resolveProduct(snapshot);
            productSnapshotRef = snapshot.snapshotBusinessId();
            unitLabor = view.otherTaskLaborCost();
        }
        return new OtherTaskRepository.OtherTaskRecord(
                taskBusinessId,
                request.workContent().strip(),
                relatedProduct == null || relatedProduct.isBlank() ? null : relatedProduct,
                productSnapshotRef,
                request.plannedQuantity(),
                unitLabor,
                null,
                1L);
    }

    private List<PlannedItem> planBoundItems(ArrangeTaskCommand command) {
        List<PlannedItem> items = new ArrayList<>();
        Set<String> seenSources = new LinkedHashSet<>();
        int itemOrder = 1;
        for (TaskItemRequest request : command.items()) {
            SourceRecord source = production.findSource(request.productionSourceBusinessId())
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.NOT_FOUND, "PRODUCTION_SOURCE_NOT_FOUND",
                            "生产来源不存在：" + request.productionSourceBusinessId()));
            DemandRecord demand = production.findDemand(source.demandBusinessId())
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.NOT_FOUND, "PRODUCTION_DEMAND_NOT_FOUND", "生产需求不存在"));

            requireTaskTypeConsistency(command.taskType(), command.processCode(), demand.demandType());
            requireTargetReachable(source.targetProcess(), command.processCode());
            if (!seenSources.add(request.productionSourceBusinessId())) {
                throw new ApiException(
                        ApiErrorType.VALIDATION, "PRODUCTION_TASK_INVALID", "同一任务不能重复同一来源明细");
            }
            items.add(new PlannedItem(
                    "TI-" + UUID.randomUUID(), itemOrder++, demand.demandBusinessId(),
                    demand.demandType(), source.productionSourceBusinessId(),
                    source.physicalRootSourceBusinessId(), null, demand.orderBusinessId(),
                    demand.orderItemBusinessId(), demand.productBusinessId(),
                    demand.productSnapshotRef(), demand.schemeBusinessId(), demand.schemeSnapshotRef(),
                    source.targetProcess(), command.processCode(), request.plannedQuantity(),
                    standardMinutesOf(demand, command.processCode())));
        }
        return items;
    }

    private static void requireTaskTypeConsistency(
            TaskType taskType, TargetProcess processCode, DemandType demandType) {
        if (taskType == TaskType.EXTRA_PRODUCTION && demandType != DemandType.NEW_PRODUCT) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_TASK_TYPE_INVALID",
                    "超额任务只允许新制需求");
        }
        if (demandType == DemandType.RETURNED_PRODUCT_REPAIR && processCode == TargetProcess.MAKING
                && taskType != TaskType.REPAIR) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_TASK_TYPE_INVALID",
                    "修复需求的制作阶段只能使用返工任务，不能新增制品");
        }
    }

    private static void requireTargetReachable(TargetProcess target, TargetProcess process) {
        if (!target.chainTo().contains(process)) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_TASK_TYPE_INVALID",
                    "执行工序不在目标工序链内：" + process + " 不在 " + target + " 之前");
        }
    }

    private int standardMinutesOf(DemandRecord demand, TargetProcess process) {
        String key = switch (process) {
            case MAKING -> "makingStandardMinutes";
            case BAGGING -> "baggingStandardMinutes";
            case SEAM_CUTTING -> "seamStandardMinutes";
        };
        try {
            JsonNode node = objectMapper.readTree(demand.productionBasisJson());
            int minutes = node.path(key).asInt(0);
            if (minutes <= 0) {
                throw new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_BASIS_INVALID",
                        "生产依据缺少工序标准分钟：" + key);
            }
            return minutes;
        } catch (JsonProcessingException exception) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_BASIS_INVALID", "生产依据无法解析");
        }
    }

    private void requireSourceAvailable(PlannedItem item) {
        SourceRecord source = production.lockSource(item.productionSourceBusinessId());
        if (OPENING_PENDING.equals(source.openingStatus())) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_SOURCE_NOT_OPENED",
                    "来源尚未完成期初初始化，不能安排任务");
        }
        // 锁来源预留行后再算可安排数量，避免并发重复占用同一数量
        int reserved = sourceReservations.lockReservedQuantity(item.productionSourceBusinessId());
        int available = availableQuantity(item, item.processCode()) - reserved;
        if (available < item.quantity()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_SOURCE_INSUFFICIENT",
                    "来源当前可安排数量不足：需要 " + item.quantity() + "，可用 " + available);
        }
    }

    /**
     * 来源当前可安排数量（基线 11 §5.2.1、§6.7）。
     *
     * <p>口径取决于本次安排的工序：制作入口取“尚未制作 + 补产缺口”；后续工序只取上一有效工序
     * 已经合格、尚未进入下一阶段的数量。修复需求不新增制品，从待处理与在制数量中取。
     */
    private int availableQuantity(PlannedItem item, TargetProcess process) {
        var projection = production.findDemandProjection(item.demandBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_PROJECTION_MISSING", "需求数量投影缺失"));
        if (item.demandType() == DemandType.RETURNED_PRODUCT_REPAIR) {
            return projection.pendingWorkQuantity() + projection.inProcessQuantity();
        }
        return process == TargetProcess.MAKING
                ? projection.pendingWorkQuantity() + projection.fulfillmentGapQuantity()
                : projection.inProcessQuantity();
    }

    private WorkloadAssessor.AssignableResult assess(
            ArrangeTaskCommand command, List<PlannedItem> planned,
            AssignmentSubjectSnapshotBundle bundle, Map<String, DailyLoadService.DayLoad> loads) {
        int dailyMinutes = bundle.dailyEffectiveMinutes();
        List<WorkloadAssessor.DetailStandard> standards = planned.stream()
                .map(item -> new WorkloadAssessor.DetailStandard(item.quantity(), item.standardMinutes()))
                .toList();

        if (command.subject().subjectType() == SubjectType.TEAM) {
            List<Long> memberOccupied = new ArrayList<>();
            for (var member : bundle.subjectView().members()) {
                memberOccupied.add(occupiedOf(loads.get(member.employeeBusinessId())));
            }
            long occupiedBefore = memberOccupied.stream().mapToLong(Long::longValue).min().orElse(0L);
            return WorkloadAssessor.normalise(workloadAssessor.assessTeam(
                    command.taskType(), standards, command.enteredStandardMinutes(), dailyMinutes,
                    occupiedBefore, bundle.subjectView().efficiencyFactor(), memberOccupied));
        }
        String employeeId = command.subject().subjectBusinessId();
        return WorkloadAssessor.normalise(workloadAssessor.assessIndividual(
                command.taskType(), standards, command.enteredStandardMinutes(), dailyMinutes,
                occupiedOf(loads.get(employeeId))));
    }

    private static long occupiedOf(DailyLoadService.DayLoad load) {
        return load == null ? 0L : load.occupiedSeconds();
    }

    private void requirePermitted(TaskType taskType, WorkloadComparison comparison, String overrideReason) {
        if (comparison == WorkloadComparison.UNAVAILABLE) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_WORKLOAD_UNAVAILABLE",
                    "缺少工时、资格、效率或有效时间依据，不能安排任务");
        }
        if (!workloadAssessor.permits(taskType, comparison, overrideReason)) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_WORKLOAD_OVER_STANDARD",
                    "超出每日有效工作时间，需要覆盖权限并填写原因");
        }
    }

    private void applySlotsAndModes(ArrangeTaskCommand command, Set<String> participants) {
        boolean otherTask = command.taskType() == TaskType.OTHER_TASK;
        String slotType = command.taskType() == TaskType.REPAIR
                ? SubjectTaskSlot.REPAIR.name() : command.taskType().name();

        for (String employeeId : participants) {
            List<DailyLoadRepository.SlotRecord> existing =
                    dailyLoads.findSlots(employeeId, command.workDate());
            String mode = dailyLoads.findWorkMode(employeeId, command.workDate()).orElse(null);

            if (otherTask) {
                boolean productionSlots = existing.stream().anyMatch(slot ->
                        !SubjectTaskSlot.OTHER_TASK.name().equals(slot.slotType())
                                && !SLOT_RELEASED.equals(slot.status()));
                if (productionSlots || (mode != null && !WORK_MODE_OTHER.equals(mode))) {
                    throw new ApiException(
                            ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_SLOT_CONFLICT",
                            "员工当日已存在生产或返工任务，不能安排其他任务");
                }
            } else if (mode != null && WORK_MODE_OTHER.equals(mode)) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_SLOT_CONFLICT",
                        "员工当日已存在其他任务，不能安排生产任务");
            }

            boolean slotOccupied = existing.stream()
                    .anyMatch(slot -> slot.slotType().equals(slotType) && !SLOT_RELEASED.equals(slot.status()));
            if (command.taskType().occupiesUniqueSlot() && slotOccupied) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_SLOT_CONFLICT",
                        "员工当日该类型任务槽位已被占用：" + slotType);
            }
            if (command.taskType().requiresNormalProduction()) {
                boolean hasNormal = existing.stream().anyMatch(slot ->
                        SubjectTaskSlot.NORMAL_PRODUCTION.name().equals(slot.slotType())
                                && !SLOT_RELEASED.equals(slot.status()));
                if (!hasNormal) {
                    throw new ApiException(
                            ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_SLOT_DEPENDENCY_MISSING",
                            "超额或返工任务必须先存在同日有效正常生产任务");
                }
            }
        }
    }

    private void applySlotWrites(ArrangeTaskCommand command, Set<String> participants, String taskBusinessId) {
        String slotType = command.taskType() == TaskType.REPAIR
                ? SubjectTaskSlot.REPAIR.name() : command.taskType().name();
        String mode = command.taskType() == TaskType.OTHER_TASK ? WORK_MODE_OTHER : WORK_MODE_PRODUCTION;
        for (String employeeId : participants) {
            String existingMode = dailyLoads.findWorkMode(employeeId, command.workDate()).orElse(null);
            if (!mode.equals(existingMode)) {
                dailyLoads.upsertWorkMode(employeeId, command.workDate(), mode);
            }
            dailyLoads.upsertSlot(employeeId, command.workDate(), slotType, taskBusinessId, SLOT_ACTIVE);
        }
    }

    private Map<String, Integer> makingQuantityByProduct(
            ArrangeTaskCommand command, List<PlannedItem> planned) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (command.processCode() != TargetProcess.MAKING) {
            return result;
        }
        if (command.taskType() != TaskType.NORMAL_PRODUCTION
                && command.taskType() != TaskType.EXTRA_PRODUCTION) {
            return result;
        }
        for (PlannedItem item : planned) {
            if (item.demandType() != DemandType.NEW_PRODUCT) {
                continue;
            }
            result.merge(item.productBusinessId(), item.quantity(), Integer::sum);
        }
        return result;
    }

    private Set<String> participantsOf(ArrangeTaskCommand command, AssignmentSubjectSnapshotBundle bundle) {
        Set<String> participants = new LinkedHashSet<>();
        for (AssignmentSubjectSnapshotView.MemberSnapshot member : bundle.subjectView().members()) {
            participants.add(member.employeeBusinessId());
        }
        if (participants.isEmpty() && command.subject().subjectType() == SubjectType.EMPLOYEE) {
            participants.add(command.subject().subjectBusinessId());
        }
        return participants;
    }

    private void saveAssessment(
            String assessmentBusinessId, String taskBusinessId, List<PlannedItem> planned,
            WorkloadAssessor.AssignableResult assessed, AssignmentSubjectSnapshotBundle bundle,
            Set<String> participants, String overrideReason) {
        assessments.insertAssessment(new AssessmentRepository.AssessmentRecord(
                assessmentBusinessId, taskBusinessId, 1L, assessed.requiredSeconds(),
                assessed.availableSeconds(), assessed.assessmentResult(),
                bundle.subjectSnapshot().snapshotBusinessId(),
                bundle.dailyWorkTimeSnapshot().snapshotBusinessId(),
                bundle.efficiencyStandardSnapshot() == null
                        ? null : bundle.efficiencyStandardSnapshot().snapshotBusinessId(),
                com.yumi.calculation.api.FormulaVersions.RULE_VERSION, assessed.inputFingerprint(),
                overrideReason));
        for (PlannedItem item : planned) {
            assessments.insertAssessmentItem(new AssessmentRepository.AssessmentItemRecord(
                    assessmentBusinessId, item.taskItemBusinessId(), item.productSnapshotRef(),
                    item.schemeSnapshotRef(), item.targetProcess().name(), item.quantity(),
                    item.standardMinutes(), (long) item.quantity() * item.standardMinutes() * 60L,
                    assessed.inputFingerprint()));
        }
        for (String employeeId : participants) {
            assessments.insertMemberLoad(new AssessmentRepository.MemberLoadRecord(
                    assessmentBusinessId, employeeId, null, 0L, assessed.availableSeconds(),
                    assessed.membersAddedSeconds()));
        }
    }

    /** 计划明细。 */
    private record PlannedItem(
            String taskItemBusinessId, int itemOrder, String demandBusinessId, DemandType demandType,
            String productionSourceBusinessId, String physicalRootSourceBusinessId,
            String repairSourceBusinessId, String orderBusinessId, String orderItemBusinessId,
            String productBusinessId, String productSnapshotRef, String schemeBusinessId,
            String schemeSnapshotRef, TargetProcess targetProcess, TargetProcess processCode,
            int quantity, int standardMinutes) {

        TaskItemRecord toRecord(String taskBusinessId) {
            return new TaskItemRecord(
                    taskItemBusinessId, taskBusinessId, itemOrder, demandBusinessId, demandType,
                    productionSourceBusinessId, physicalRootSourceBusinessId, repairSourceBusinessId,
                    orderBusinessId, orderItemBusinessId, productBusinessId, productSnapshotRef,
                    schemeBusinessId, schemeSnapshotRef, targetProcess, quantity, standardMinutes,
                    "PENDING", 1L, 1L);
        }
    }
}

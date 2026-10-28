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
import com.yumi.production.api.AdjustTaskCommand;
import com.yumi.production.api.AvailableProductionSourceView;
import com.yumi.production.api.AvailableRepairSourceView;
import com.yumi.production.api.ArrangeTaskCommand;
import com.yumi.production.api.CancelTaskCommand;
import com.yumi.production.api.DemandType;
import com.yumi.production.api.SubjectRef;
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
import com.yumi.production.application.port.RepairSourceRepository;
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
import com.yumi.workforce.api.ChangeWorkforceActivityCommand;
import com.yumi.workforce.api.PreparedActivityReservation;
import com.yumi.workforce.api.PreparedActivityReservationChange;
import com.yumi.workforce.api.ReserveWorkforceActivityCommand;
import com.yumi.workforce.api.ReleaseWorkforceActivityCommand;
import com.yumi.workforce.api.WorkforceActivityReservationParticipant;
import com.yumi.workforce.api.WorkforceActivitySourceRef;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
    private final RepairSourceRepository repairSources;
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
            RepairSourceRepository repairSources,
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
        this.repairSources = repairSources;
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
    public TaskView adjust(AdjustTaskCommand command) {
        authorization.require(PermissionCodes.PRODUCTION_SCHEDULE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_ADJUSTMENT", "任务调整命令不能为空");
        }
        return transactionTemplate.execute(status -> doAdjust(command));
    }

    private TaskView doAdjust(AdjustTaskCommand command) {
        Instant now = clock.instant();
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
        TaskRecord task = tasks.lockTask(command.taskBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_TASK_NOT_FOUND", "任务不存在"));
        if (task.status() == TaskStatus.CANCELLED || task.status() == TaskStatus.VERIFIED) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_TASK_NOT_ADJUSTABLE",
                    "任务已取消或已全部核验，不能调整");
        }
        List<TaskItemRecord> items = tasks.findTaskItems(task.taskBusinessId());
        if (task.taskType() == TaskType.OTHER_TASK) {
            return adjustOtherTask(task, command, now, operatorId);
        }
        return adjustBoundTask(task, items, command, now, operatorId);
    }

    private TaskView adjustBoundTask(
            TaskRecord task, List<TaskItemRecord> allItems, AdjustTaskCommand command,
            Instant now, String operatorId) {
        validateBoundAdjustCommand(task.taskType(), command);
        List<TaskItemRecord> pending = allItems.stream()
                .filter(item -> "PENDING".equals(item.verificationStatus()))
                .toList();
        if (pending.isEmpty()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_TASK_NOT_ADJUSTABLE",
                    "任务没有可调整的未核验明细");
        }
        boolean hasVerified = allItems.stream()
                .anyMatch(item -> "VERIFIED".equals(item.verificationStatus()));
        if (hasVerified && task.processCode() != command.processCode()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_PROCESS_LOCKED_AFTER_VERIFICATION",
                    "任务已有核验事实，不能修改工序");
        }

        long newVersion = task.activeAssignmentVersion() + 1;
        List<PlannedItem> planned = planAdjustedItems(
                task, allItems, pending, command);
        Set<String> keptItemIds = planned.stream()
                .map(PlannedItem::taskItemBusinessId)
                .filter(id -> pending.stream().anyMatch(item -> item.taskItemBusinessId().equals(id)))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<TaskItemRecord> removed = pending.stream()
                .filter(item -> !keptItemIds.contains(item.taskItemBusinessId()))
                .toList();

        Set<String> oldParticipants = new LinkedHashSet<>(
                tasks.findParticipantEmployeeIds(task.taskBusinessId()));
        boolean moved = !task.workDate().equals(command.workDate())
                || !task.subject().equals(command.subject())
                || (task.processCode() == TargetProcess.MAKING
                        && command.processCode() != TargetProcess.MAKING);
        if (task.taskType() == TaskType.NORMAL_PRODUCTION && moved) {
            requireNoDependentTasks(task, oldParticipants);
        }

        PreparedActivityReservationChange change = workforce.prepareChange(
                new ChangeWorkforceActivityCommand(
                        new WorkforceActivitySourceRef(SOURCE_MODULE, SOURCE_TYPE, task.taskBusinessId()),
                        assignmentSubject(command.subject()),
                        command.processCode().name(), command.workDate(), List.of()));
        AssignmentSubjectSnapshotBundle bundle = change.prepared().bundle();
        Set<String> newParticipants = participantsOf(command.subject(), bundle);

        Map<String, DailyLoadService.DayLoad> newLoads = lockAdjustmentDays(
                oldParticipants, task.workDate(), newParticipants, command.workDate());
        validateSlotsFor(
                task.taskType(), newParticipants, command.workDate(), task.taskBusinessId());

        Map<CapacityKey, Integer> oldMaking = makingOfPending(
                pending, task.workDate(), task.processCode(), task.taskType());
        Map<CapacityKey, Integer> newMaking = makingOfPlanned(
                planned, command.workDate(), command.processCode(), task.taskType());
        Map<CapacityKey, NewProductCapacityService.CapacityLock> capacityLocks =
                lockAdjustmentCapacity(oldMaking, newMaking);

        Map<String, Integer> oldBySource = pending.stream()
                .filter(item -> item.repairSourceBusinessId() == null)
                .collect(java.util.stream.Collectors.groupingBy(
                        TaskItemRecord::productionSourceBusinessId,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.summingInt(TaskItemRecord::plannedQuantity)));
        Map<String, Integer> newBySource = planned.stream()
                .filter(item -> item.repairSourceBusinessId() == null)
                .collect(java.util.stream.Collectors.groupingBy(
                        PlannedItem::productionSourceBusinessId,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.summingInt(PlannedItem::quantity)));
        Map<String, PlannedItem> firstNewBySource = new LinkedHashMap<>();
        planned.stream().filter(item -> item.repairSourceBusinessId() == null)
                .forEach(item -> firstNewBySource.putIfAbsent(
                        item.productionSourceBusinessId(), item));
        Map<String, Integer> oldRepairBySource = pending.stream()
                .filter(item -> item.repairSourceBusinessId() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        TaskItemRecord::repairSourceBusinessId,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.summingInt(TaskItemRecord::plannedQuantity)));
        Map<String, Integer> newRepairBySource = planned.stream()
                .filter(item -> item.repairSourceBusinessId() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        PlannedItem::repairSourceBusinessId,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.summingInt(PlannedItem::quantity)));
        lockAndValidateSources(
                oldBySource, newBySource, firstNewBySource, command.processCode());
        lockAndValidateRepairSources(
                oldRepairBySource, newRepairBySource);

        long releasedSeconds = pending.stream()
                .mapToLong(item -> (long) item.plannedQuantity() * item.itemStandardMinutes() * 60L)
                .sum();
        for (String employeeId : oldParticipants) {
            dailyLoadService.releasePlan(employeeId, task.workDate(), releasedSeconds);
        }
        newLoads = currentLoads(newParticipants, command.workDate());
        WorkloadAssessor.AssignableResult assessed = assess(
                task.taskType(), command.subject(), null, planned, bundle, newLoads);
        requirePermitted(task.taskType(), assessed.comparison(), command.overrideReason());

        applyCapacityAdjustment(capacityLocks, oldMaking, newMaking);
        applySourceReservationChanges(
                oldBySource, newBySource, oldRepairBySource, newRepairBySource, pending, now,
                operatorId);

        for (TaskItemRecord item : removed) {
            tasks.cancelTaskItem(
                    item.taskItemBusinessId(), item.plannedQuantity(), newVersion);
            tasks.insertItemAssignmentFact(item, newVersion, "REMOVE", now, operatorId);
        }
        for (PlannedItem item : planned) {
            TaskItemRecord record = item.toRecord(task.taskBusinessId(), newVersion);
            String operation = keptItemIds.contains(item.taskItemBusinessId()) ? "ADJUST" : "ADD";
            if ("ADJUST".equals(operation)) {
                tasks.updateTaskItemPlan(record);
            } else {
                tasks.insertTaskItem(record);
            }
            tasks.insertItemAssignmentFact(record, newVersion, operation, now, operatorId);
            sourceReservations.upsertActiveHold(new SourceReservationRepository.SourceHold(
                    task.taskBusinessId(), record.taskItemBusinessId(),
                    record.productionSourceBusinessId(), record.repairSourceBusinessId(),
                    record.plannedQuantity(), "ACTIVE"));
            if (record.repairSourceBusinessId() != null) {
                repairSources.recordFact(
                        record.repairSourceBusinessId(), "RESERVED", record.plannedQuantity(),
                        task.taskBusinessId(), record.taskItemBusinessId(), now, operatorId);
            }
        }

        int verifiedQuantity = allItems.stream()
                .filter(item -> "VERIFIED".equals(item.verificationStatus()))
                .mapToInt(TaskItemRecord::plannedQuantity)
                .sum();
        long oldBaseSeconds = allItems.stream()
                .mapToLong(item -> (long) item.plannedQuantity() * item.itemStandardMinutes() * 60L)
                .sum();
        long verifiedBaseSeconds = allItems.stream()
                .filter(item -> "VERIFIED".equals(item.verificationStatus()))
                .mapToLong(item -> (long) item.plannedQuantity() * item.itemStandardMinutes() * 60L)
                .sum();
        long verifiedLoad = oldBaseSeconds == 0
                ? 0L
                : Math.round((double) task.taskStandardSeconds()
                        * verifiedBaseSeconds / oldBaseSeconds);
        int totalQuantity = verifiedQuantity + planned.stream().mapToInt(PlannedItem::quantity).sum();
        long totalSeconds = verifiedLoad + assessed.requiredSeconds();
        String assessmentBusinessId = "ASMT-" + UUID.randomUUID();
        saveAssessment(assessmentBusinessId, task.taskBusinessId(), newVersion, planned, assessed,
                bundle, newParticipants, command.overrideReason());

        TaskRecord updated = new TaskRecord(
                task.id(), task.taskBusinessId(), task.taskNo(), task.taskType(),
                command.workDate(), command.processCode(), totalQuantity, null, totalSeconds,
                command.subject(), bundle.subjectSnapshot().snapshotBusinessId(),
                assessmentBusinessId,
                bundle.dailyWorkTimeSnapshot().snapshotBusinessId(),
                bundle.efficiencyStandardSnapshot() == null
                        ? null : bundle.efficiencyStandardSnapshot().snapshotBusinessId(),
                hasVerified ? TaskStatus.PARTIALLY_VERIFIED : TaskStatus.ARRANGED,
                newVersion, task.rowVersion() + 1, task.createdAt(), task.createdBy(), now);
        tasks.updateTaskPlan(updated);
        tasks.insertAssignmentFact(
                task.taskBusinessId(), newVersion, "ADJUST", task.subjectSnapshotRef(),
                bundle.subjectSnapshot().snapshotBusinessId(), task.workDate(), command.workDate(),
                task.processCode() == null ? null : task.processCode().name(),
                command.processCode().name(), task.plannedWorkQuantity(), totalQuantity,
                now, operatorId);

        dailyLoadService.reservePlan(newLoads, assessed.membersAddedSeconds());
        applyActiveReferences(task.taskBusinessId(), oldParticipants, newParticipants,
                command.subject(), command.workDate(), assessed.membersAddedSeconds());
        applySlotChanges(
                task, command.workDate(), oldParticipants, newParticipants, hasVerified);
        workforce.commitChange(change);
        return viewOf(tasks.findTask(task.taskBusinessId()).orElseThrow());
    }

    private TaskView adjustOtherTask(
            TaskRecord task, AdjustTaskCommand command, Instant now, String operatorId) {
        validateOtherTaskAdjustCommand(command);
        if (task.status() != TaskStatus.ARRANGED) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_TASK_NOT_ADJUSTABLE",
                    "其他任务已核验或取消，不能调整");
        }
        long newVersion = task.activeAssignmentVersion() + 1;
        PreparedActivityReservationChange change = workforce.prepareChange(
                new ChangeWorkforceActivityCommand(
                        new WorkforceActivitySourceRef(SOURCE_MODULE, SOURCE_TYPE, task.taskBusinessId()),
                        assignmentSubject(command.subject()), null, command.workDate(), List.of()));
        AssignmentSubjectSnapshotBundle bundle = change.prepared().bundle();
        Set<String> oldParticipants = new LinkedHashSet<>(
                tasks.findParticipantEmployeeIds(task.taskBusinessId()));
        Set<String> newParticipants = participantsOf(command.subject(), bundle);

        Map<String, DailyLoadService.DayLoad> newLoads = lockAdjustmentDays(
                oldParticipants, task.workDate(), newParticipants, command.workDate());
        validateSlotsFor(
                TaskType.OTHER_TASK, newParticipants, command.workDate(), task.taskBusinessId());

        for (String employeeId : oldParticipants) {
            dailyLoadService.releasePlan(
                    employeeId, task.workDate(), task.taskStandardSeconds());
        }
        newLoads = currentLoads(newParticipants, command.workDate());
        WorkloadAssessor.AssignableResult assessed = assess(
                TaskType.OTHER_TASK, command.subject(), command.enteredStandardMinutes(),
                List.of(), bundle, newLoads);
        requirePermitted(TaskType.OTHER_TASK, assessed.comparison(), command.overrideReason());

        otherTasks.update(otherTaskRecord(command.otherTask(), task.taskBusinessId()));
        String assessmentBusinessId = "ASMT-" + UUID.randomUUID();
        saveAssessment(assessmentBusinessId, task.taskBusinessId(), newVersion, List.of(), assessed,
                bundle, newParticipants, command.overrideReason());
        int totalQuantity = command.otherTask().plannedQuantity() == null
                ? 0 : command.otherTask().plannedQuantity();

        TaskRecord updated = new TaskRecord(
                task.id(), task.taskBusinessId(), task.taskNo(), task.taskType(),
                command.workDate(), null, totalQuantity, command.enteredStandardMinutes(),
                assessed.requiredSeconds(), command.subject(),
                bundle.subjectSnapshot().snapshotBusinessId(), assessmentBusinessId,
                bundle.dailyWorkTimeSnapshot().snapshotBusinessId(),
                bundle.efficiencyStandardSnapshot() == null
                        ? null : bundle.efficiencyStandardSnapshot().snapshotBusinessId(),
                TaskStatus.ARRANGED, newVersion, task.rowVersion() + 1,
                task.createdAt(), task.createdBy(), now);
        tasks.updateTaskPlan(updated);
        tasks.insertAssignmentFact(
                task.taskBusinessId(), newVersion, "ADJUST", task.subjectSnapshotRef(),
                bundle.subjectSnapshot().snapshotBusinessId(), task.workDate(), command.workDate(),
                null, null, task.plannedWorkQuantity(), totalQuantity, now, operatorId);

        dailyLoadService.reservePlan(newLoads, assessed.membersAddedSeconds());
        applyActiveReferences(task.taskBusinessId(), oldParticipants, newParticipants,
                command.subject(), command.workDate(), assessed.membersAddedSeconds());
        applySlotChanges(
                task, command.workDate(), oldParticipants, newParticipants, false);
        workforce.commitChange(change);
        return viewOf(tasks.findTask(task.taskBusinessId()).orElseThrow());
    }

    @Override
    public java.util.List<AvailableProductionSourceView> availableSources(TargetProcess processCode) {
        authorization.require(PermissionCodes.PRODUCTION_READ, ResourceScope.GLOBAL);
        if (processCode == null) {
            return java.util.List.of();
        }
        return production.listAvailableSources().stream()
                .filter(source -> !OPENING_PENDING.equals(source.openingStatus()))
                .map(source -> {
                    int available = source.demandType() == DemandType.RETURNED_PRODUCT_REPAIR
                            ? source.pendingWorkQuantity() + source.inProcessQuantity()
                            : processCode == TargetProcess.MAKING
                                    ? source.pendingWorkQuantity() + source.fulfillmentGapQuantity()
                                    : source.inProcessQuantity();
                    return new AvailableProductionSourceView(
                            source.productionSourceBusinessId(), source.demandBusinessId(),
                            source.demandType(), source.productBusinessId(), source.schemeBusinessId(),
                            source.targetProcess(), source.initialQuantity(),
                            source.reservedQuantity(), Math.max(0, available - source.reservedQuantity()));
                })
                .filter(source -> source.availableQuantity() > 0)
                .toList();
    }

    @Override
    public List<AvailableRepairSourceView> availableRepairSources(TargetProcess processCode) {
        authorization.require(PermissionCodes.PRODUCTION_READ, ResourceScope.GLOBAL);
        if (processCode == null) {
            return List.of();
        }
        return repairSources.listAvailable().stream()
                .filter(source -> source.repairProcessCode() == processCode)
                .map(source -> new AvailableRepairSourceView(
                        source.repairSourceBusinessId(), source.originType(),
                        source.demandBusinessId(), source.productionSourceBusinessId(),
                        source.demandType(), source.productBusinessId(), source.schemeBusinessId(),
                        source.targetProcess(), source.repairProcessCode(),
                        source.originalQuantity(), source.completedQuantity(),
                        source.reservedQuantity(),
                        Math.max(0, source.availableQuantity() - source.reservedQuantity())))
                .filter(source -> source.availableQuantity() > 0)
                .toList();
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
        Instant now = clock.instant();
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
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
                .anyMatch(item -> "VERIFIED".equals(item.verificationStatus()));
        List<String> participants = tasks.findParticipantEmployeeIds(task.taskBusinessId());

        var preparedRelease = workforce.prepareRelease(
                new ReleaseWorkforceActivityCommand(new WorkforceActivitySourceRef(
                        SOURCE_MODULE, SOURCE_TYPE, task.taskBusinessId())));
        dailyLoadService.lockDays(participants, task.workDate());

        Map<String, Integer> makingByProduct = new LinkedHashMap<>();
        if (task.processCode() == TargetProcess.MAKING) {
            for (TaskItemRecord item : pending) {
                if (item.demandType() == com.yumi.production.api.DemandType.NEW_PRODUCT
                        && item.repairSourceBusinessId() == null) {
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
        pending.stream().map(TaskItemRecord::repairSourceBusinessId)
                .filter(id -> id != null).distinct().sorted()
                .forEach(repairSources::lock);

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
            if (item.repairSourceBusinessId() != null) {
                repairSources.release(
                        item.repairSourceBusinessId(), item.plannedQuantity());
                repairSources.recordFact(
                        item.repairSourceBusinessId(), "RELEASED", item.plannedQuantity(),
                        item.taskBusinessId(), item.taskItemBusinessId(), now, operatorId);
            } else {
                sourceReservations.release(
                        item.productionSourceBusinessId(), item.plannedQuantity());
            }
            long seconds = (long) item.plannedQuantity() * item.itemStandardMinutes() * 60L;
            for (String employeeId : participants) {
                dailyLoadService.releasePlan(employeeId, task.workDate(), seconds);
            }
            if (task.processCode() == TargetProcess.MAKING
                    && item.demandType() == com.yumi.production.api.DemandType.NEW_PRODUCT
                    && item.repairSourceBusinessId() == null) {
                capacityService.releasePlan(
                        capacityLocks.get(item.productBusinessId()), item.plannedQuantity());
            }
        }
        workforce.commitRelease(preparedRelease);

        String slotType = task.taskType() == TaskType.REPAIR
                ? SubjectTaskSlot.REPAIR.name() : task.taskType().name();
        if (hasVerified) {
            int remainingPlanned = items.stream()
                    .filter(item -> "VERIFIED".equals(item.verificationStatus()))
                    .mapToInt(TaskItemRecord::plannedQuantity).sum();
            long remainingSeconds = items.stream()
                    .filter(item -> "VERIFIED".equals(item.verificationStatus()))
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

    @Override
    public List<TaskView> listTasks(java.time.LocalDate startDate, java.time.LocalDate endDate) {
        authorization.require(PermissionCodes.PRODUCTION_READ, ResourceScope.GLOBAL);
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "TASK_DATE_RANGE_INVALID", "开始日期不能晚于结束日期");
        }
        return tasks.findTasksBetween(startDate, endDate).stream().map(this::viewOf).toList();
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

    private void validateBoundAdjustCommand(TaskType taskType, AdjustTaskCommand command) {
        if (command.items().isEmpty() || command.processCode() == null) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCTION_TASK_INVALID",
                    "生产/返工任务调整必须给出工序与至少一条明细");
        }
        if (command.enteredStandardMinutes() != null || command.otherTask() != null) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCTION_TASK_INVALID",
                    "生产/返工任务不使用其他任务字段");
        }
        if (taskType == TaskType.EXTRA_PRODUCTION && command.processCode() != TargetProcess.MAKING) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCTION_TASK_INVALID",
                    "超额任务工序固定为 MAKING");
        }
        if (taskType == TaskType.REPAIR && command.items().size() != 1) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCTION_TASK_INVALID",
                    "返工任务只允许关联一个返工来源");
        }
    }

    private void validateOtherTaskAdjustCommand(AdjustTaskCommand command) {
        if (command.processCode() != null || !command.items().isEmpty()
                || command.enteredStandardMinutes() == null
                || command.enteredStandardMinutes() <= 0 || command.otherTask() == null) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCTION_TASK_INVALID",
                    "其他任务调整必须给出工作内容与整体标准分钟，且不关联来源与工序");
        }
    }

    private List<PlannedItem> planAdjustedItems(
            TaskRecord task, List<TaskItemRecord> allItems, List<TaskItemRecord> pending,
            AdjustTaskCommand command) {
        Map<String, TaskItemRecord> pendingById = new LinkedHashMap<>();
        pending.forEach(item -> pendingById.put(item.taskItemBusinessId(), item));
        Map<String, TaskItemRecord> allById = new LinkedHashMap<>();
        allItems.forEach(item -> allById.put(item.taskItemBusinessId(), item));

        Set<String> seenIds = new LinkedHashSet<>();
        Set<String> seenSources = new LinkedHashSet<>();
        int nextOrder = allItems.stream()
                .mapToInt(TaskItemRecord::itemOrder).max().orElse(0) + 1;
        List<PlannedItem> planned = new ArrayList<>();
        for (AdjustTaskCommand.ItemRequest request : command.items()) {
            TaskItemRecord existing = null;
            if (request.existing()) {
                existing = pendingById.get(request.taskItemBusinessId());
                if (existing == null) {
                    TaskItemRecord other = allById.get(request.taskItemBusinessId());
                    if (other != null && "VERIFIED".equals(other.verificationStatus())) {
                        throw new ApiException(
                                ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_ITEM_ALREADY_VERIFIED",
                                "已核验明细不能调整：" + request.taskItemBusinessId());
                    }
                    throw new ApiException(
                            ApiErrorType.NOT_FOUND, "PRODUCTION_TASK_ITEM_NOT_FOUND",
                            "任务明细不存在或不属于当前任务：" + request.taskItemBusinessId());
                }
                if (!seenIds.add(existing.taskItemBusinessId())) {
                    throw new ApiException(
                            ApiErrorType.VALIDATION, "PRODUCTION_TASK_ITEM_DUPLICATED",
                            "同一明细不能重复提交");
                }
            }
            String repairSourceId = request.repairSourceBusinessId() != null
                    ? request.repairSourceBusinessId()
                    : (existing == null ? null : existing.repairSourceBusinessId());
            if (repairSourceId != null) {
                requireRepairTask(task.taskType());
                if (!seenSources.add(repairSourceId)) {
                    throw new ApiException(
                            ApiErrorType.VALIDATION, "PRODUCTION_TASK_INVALID",
                            "同一任务不能重复同一来源明细");
                }
                planned.add(planRepairItem(
                        task.taskType(), command.processCode(), repairSourceId,
                        existing == null ? "TI-" + UUID.randomUUID() : existing.taskItemBusinessId(),
                        existing == null ? nextOrder++ : existing.itemOrder(),
                        request.plannedQuantity()));
                continue;
            }
            SourceRecord source = production.findSource(request.productionSourceBusinessId())
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.NOT_FOUND, "PRODUCTION_SOURCE_NOT_FOUND",
                            "生产来源不存在：" + request.productionSourceBusinessId()));
            DemandRecord demand = production.findDemand(source.demandBusinessId())
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.NOT_FOUND, "PRODUCTION_DEMAND_NOT_FOUND", "生产需求不存在"));
            requireTaskTypeConsistency(task.taskType(), command.processCode(), demand.demandType());
            requireRepairSourceBinding(task.taskType(), demand.demandType());
            requireTargetReachable(source.targetProcess(), command.processCode());
            if (!seenSources.add(source.productionSourceBusinessId())) {
                throw new ApiException(
                        ApiErrorType.VALIDATION, "PRODUCTION_TASK_INVALID", "同一任务不能重复同一来源明细");
            }
            planned.add(new PlannedItem(
                    existing == null ? "TI-" + UUID.randomUUID() : existing.taskItemBusinessId(),
                    existing == null ? nextOrder++ : existing.itemOrder(),
                    demand.demandBusinessId(), demand.demandType(),
                    source.productionSourceBusinessId(), source.physicalRootSourceBusinessId(),
                    null,
                    demand.orderBusinessId(), demand.orderItemBusinessId(),
                    demand.productBusinessId(), demand.productSnapshotRef(),
                    demand.schemeBusinessId(), demand.schemeSnapshotRef(),
                    source.targetProcess(), command.processCode(), request.plannedQuantity(),
                    standardMinutesOf(demand, command.processCode())));
        }
        return planned;
    }

    private void requireNoDependentTasks(TaskRecord task, Set<String> participants) {
        for (String employeeId : participants) {
            for (TaskRecord other : tasks.findOtherActiveTasksOfParticipant(
                    employeeId, task.workDate(), task.taskBusinessId())) {
                if (other.taskType() == TaskType.EXTRA_PRODUCTION
                        || other.taskType() == TaskType.REPAIR) {
                    throw new ApiException(
                            ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_DEPENDENT_TASK_EXISTS",
                            "正常任务存在依赖的超额或返工任务，请先取消或调整：" + other.taskNo());
                }
            }
        }
    }

    private Map<CapacityKey, Integer> makingOfPending(
            List<TaskItemRecord> pending, LocalDate workDate,
            TargetProcess processCode, TaskType taskType) {
        Map<CapacityKey, Integer> making = new LinkedHashMap<>();
        if (processCode != TargetProcess.MAKING
                || (taskType != TaskType.NORMAL_PRODUCTION
                        && taskType != TaskType.EXTRA_PRODUCTION)) {
            return making;
        }
        pending.stream()
                .filter(item -> item.demandType() == DemandType.NEW_PRODUCT)
                .forEach(item -> making.merge(
                        new CapacityKey(item.productBusinessId(), workDate),
                        item.plannedQuantity(), Integer::sum));
        return making;
    }

    private Map<CapacityKey, Integer> makingOfPlanned(
            List<PlannedItem> planned, LocalDate workDate,
            TargetProcess processCode, TaskType taskType) {
        Map<CapacityKey, Integer> making = new LinkedHashMap<>();
        if (processCode != TargetProcess.MAKING
                || (taskType != TaskType.NORMAL_PRODUCTION
                        && taskType != TaskType.EXTRA_PRODUCTION)) {
            return making;
        }
        planned.stream()
                .filter(item -> item.demandType() == DemandType.NEW_PRODUCT)
                .forEach(item -> making.merge(
                        new CapacityKey(item.productBusinessId(), workDate),
                        item.quantity(), Integer::sum));
        return making;
    }

    private Map<CapacityKey, NewProductCapacityService.CapacityLock> lockAdjustmentCapacity(
            Map<CapacityKey, Integer> oldMaking, Map<CapacityKey, Integer> newMaking) {
        TreeSet<CapacityKey> keys = new TreeSet<>(
                Comparator.comparing(CapacityKey::productBusinessId)
                        .thenComparing(CapacityKey::workDate));
        keys.addAll(oldMaking.keySet());
        keys.addAll(newMaking.keySet());
        Map<CapacityKey, NewProductCapacityService.CapacityLock> locks = new LinkedHashMap<>();
        for (CapacityKey key : keys) {
            if (newMaking.containsKey(key)) {
                ProductSnapshotRef snapshot =
                        productSnapshots.acquireProductSnapshot(key.productBusinessId());
                int limit = productSnapshots.resolveProduct(snapshot).dailyCapacity();
                locks.put(key, capacityService.lockOne(
                        key.productBusinessId(), key.workDate(), limit,
                        snapshot.sourceContentVersion()));
            } else {
                locks.put(key, capacityService.lockKeepingLimit(
                        key.productBusinessId(), key.workDate()));
            }
        }
        return locks;
    }

    private void applyCapacityAdjustment(
            Map<CapacityKey, NewProductCapacityService.CapacityLock> locks,
            Map<CapacityKey, Integer> oldMaking, Map<CapacityKey, Integer> newMaking) {
        for (var entry : locks.entrySet()) {
            int oldQuantity = oldMaking.getOrDefault(entry.getKey(), 0);
            int newQuantity = newMaking.getOrDefault(entry.getKey(), 0);
            if (oldQuantity > 0 && newQuantity > 0) {
                capacityService.adjustPlan(entry.getValue(), oldQuantity, newQuantity);
            } else if (oldQuantity > 0) {
                capacityService.releasePlan(entry.getValue(), oldQuantity);
            } else if (newQuantity > 0) {
                capacityService.reservePlan(entry.getValue(), newQuantity);
            }
        }
    }

    private void lockAndValidateSources(
            Map<String, Integer> oldBySource, Map<String, Integer> newBySource,
            Map<String, PlannedItem> firstNewBySource, TargetProcess processCode) {
        Set<String> sources = new TreeSet<>();
        sources.addAll(oldBySource.keySet());
        sources.addAll(newBySource.keySet());
        for (String sourceId : sources) {
            SourceRecord source = production.lockSource(sourceId);
            int reserved = sourceReservations.lockReservedQuantity(sourceId);
            int newQuantity = newBySource.getOrDefault(sourceId, 0);
            if (newQuantity <= 0) {
                continue;
            }
            if (OPENING_PENDING.equals(source.openingStatus())) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_SOURCE_NOT_OPENED",
                        "来源尚未完成期初初始化，不能安排任务");
            }
            int effectiveReserved = reserved - oldBySource.getOrDefault(sourceId, 0);
            if (effectiveReserved < 0) {
                throw new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_SOURCE_RESERVATION_INCONSISTENT",
                        "来源预留不守恒：" + sourceId);
            }
            PlannedItem reference = firstNewBySource.get(sourceId);
            int available = availableQuantity(reference, processCode) - effectiveReserved;
            if (available < newQuantity) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_SOURCE_INSUFFICIENT",
                        "来源当前可安排数量不足：需要 " + newQuantity + "，可用 " + available);
            }
        }
    }

    private void lockAndValidateRepairSources(
            Map<String, Integer> oldBySource, Map<String, Integer> newBySource) {
        Set<String> sources = new TreeSet<>();
        sources.addAll(oldBySource.keySet());
        sources.addAll(newBySource.keySet());
        for (String sourceId : sources) {
            RepairSourceRepository.RepairSourceRecord repair = repairSources.lock(sourceId);
            int newQuantity = newBySource.getOrDefault(sourceId, 0);
            if (newQuantity <= 0) {
                continue;
            }
            if (!"ACTIVE".equals(repair.status())) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_REPAIR_SOURCE_NOT_AVAILABLE",
                        "返工来源已失效，不能安排任务：" + sourceId);
            }
            int effectiveReserved = repair.reservedQuantity()
                    - oldBySource.getOrDefault(sourceId, 0);
            if (effectiveReserved < 0) {
                throw new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_REPAIR_SOURCE_INCONSISTENT",
                        "返工来源预留不守恒：" + sourceId);
            }
            int available = repair.availableQuantity() - effectiveReserved;
            if (available < newQuantity) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_REPAIR_SOURCE_INSUFFICIENT",
                        "返工来源可安排数量不足：需要 " + newQuantity + "，可用 " + available);
            }
        }
    }

    private void applySourceReservationChanges(
            Map<String, Integer> oldBySource, Map<String, Integer> newBySource,
            Map<String, Integer> oldRepairBySource, Map<String, Integer> newRepairBySource,
            List<TaskItemRecord> pending, Instant now, String operatorId) {
        for (TaskItemRecord item : pending) {
            sourceReservations.releaseHold(item.taskItemBusinessId());
            if (item.repairSourceBusinessId() != null) {
                repairSources.recordFact(
                        item.repairSourceBusinessId(), "RELEASED", item.plannedQuantity(),
                        item.taskBusinessId(), item.taskItemBusinessId(), now, operatorId);
            }
        }
        for (var entry : oldBySource.entrySet()) {
            if (entry.getValue() > 0) {
                sourceReservations.release(entry.getKey(), entry.getValue());
            }
        }
        for (var entry : newBySource.entrySet()) {
            if (entry.getValue() > 0) {
                sourceReservations.reserve(entry.getKey(), entry.getValue());
            }
        }
        for (var entry : oldRepairBySource.entrySet()) {
            if (entry.getValue() > 0) {
                repairSources.release(entry.getKey(), entry.getValue());
            }
        }
        for (var entry : newRepairBySource.entrySet()) {
            if (entry.getValue() > 0) {
                repairSources.reserve(entry.getKey(), entry.getValue());
            }
        }
    }

    private Map<String, DailyLoadService.DayLoad> lockAdjustmentDays(
            Set<String> oldParticipants, LocalDate oldWorkDate,
            Set<String> newParticipants, LocalDate newWorkDate) {
        TreeSet<DayKey> keys = new TreeSet<>(
                Comparator.comparing(DayKey::employeeBusinessId)
                        .thenComparing(DayKey::workDate));
        oldParticipants.forEach(employeeId ->
                keys.add(new DayKey(employeeId, oldWorkDate)));
        newParticipants.forEach(employeeId ->
                keys.add(new DayKey(employeeId, newWorkDate)));
        Map<String, DailyLoadService.DayLoad> newLoads = new LinkedHashMap<>();
        for (DayKey key : keys) {
            DailyLoadService.DayLoad load =
                    dailyLoadService.lockDay(key.employeeBusinessId(), key.workDate());
            if (key.workDate().equals(newWorkDate)
                    && newParticipants.contains(key.employeeBusinessId())) {
                newLoads.put(key.employeeBusinessId(), load);
            }
        }
        return newLoads;
    }

    private Map<String, DailyLoadService.DayLoad> currentLoads(
            Set<String> participants, LocalDate workDate) {
        Map<String, DailyLoadService.DayLoad> loads = new LinkedHashMap<>();
        participants.stream().sorted().forEach(employeeId ->
                loads.put(employeeId, dailyLoadService.load(employeeId, workDate)));
        return loads;
    }

    private void applyActiveReferences(
            String taskBusinessId, Set<String> oldParticipants, Set<String> newParticipants,
            SubjectRef subject, LocalDate workDate, long occupiedSeconds) {
        for (String employeeId : oldParticipants) {
            if (!newParticipants.contains(employeeId)) {
                dailyLoads.releaseActiveParticipant(taskBusinessId, employeeId);
            }
        }
        for (String employeeId : newParticipants) {
            dailyLoads.insertActiveParticipant(
                    taskBusinessId, employeeId, subject.subjectType().name(),
                    subject.subjectBusinessId(), workDate, occupiedSeconds);
        }
        dailyLoads.insertActiveSubject(
                taskBusinessId, subject.subjectType().name(), subject.subjectBusinessId(), workDate);
    }

    private void applySlotChanges(
            TaskRecord task, LocalDate newWorkDate, Set<String> oldParticipants,
            Set<String> newParticipants, boolean hasVerified) {
        String slotType = slotTypeOf(task.taskType());
        boolean sameDate = task.workDate().equals(newWorkDate);
        for (String employeeId : oldParticipants) {
            if (sameDate && newParticipants.contains(employeeId)) {
                continue;
            }
            dailyLoads.updateSlotStatusForTask(
                    employeeId, task.workDate(), slotType, task.taskBusinessId(),
                    hasVerified ? "RETAINED" : SLOT_RELEASED);
            if (!hasVerified) {
                releaseModeIfUnused(employeeId, task.workDate());
            }
        }
        for (String employeeId : newParticipants) {
            if (sameDate && oldParticipants.contains(employeeId)) {
                continue;
            }
            String mode = task.taskType() == TaskType.OTHER_TASK
                    ? WORK_MODE_OTHER : WORK_MODE_PRODUCTION;
            if (!mode.equals(dailyLoads.findWorkMode(employeeId, newWorkDate).orElse(null))) {
                dailyLoads.upsertWorkMode(employeeId, newWorkDate, mode);
            }
            dailyLoads.upsertSlot(
                    employeeId, newWorkDate, slotType, task.taskBusinessId(), SLOT_ACTIVE);
        }
    }

    private void releaseModeIfUnused(String employeeId, LocalDate workDate) {
        boolean retained = dailyLoads.findSlots(employeeId, workDate).stream()
                .anyMatch(slot -> !SLOT_RELEASED.equals(slot.status()));
        if (!retained) {
            dailyLoads.releaseWorkMode(employeeId, workDate);
        }
    }

    private static String slotTypeOf(TaskType taskType) {
        return taskType == TaskType.REPAIR
                ? SubjectTaskSlot.REPAIR.name() : taskType.name();
    }

    private static AssignmentSubjectRef assignmentSubject(SubjectRef subject) {
        return new AssignmentSubjectRef(
                subject.subjectType() == SubjectType.TEAM
                        ? AssignmentSubjectType.TEAM : AssignmentSubjectType.EMPLOYEE,
                subject.subjectBusinessId());
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
        Set<String> participants = participantsOf(command.subject(), bundle);

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

        WorkloadAssessor.AssignableResult assessed = assess(
                command.taskType(), command.subject(), command.enteredStandardMinutes(),
                planned, bundle, loads);
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
            TaskItemRecord record = item.toRecord(taskBusinessId, 1L);
            tasks.insertTaskItem(record);
            tasks.insertItemAssignmentFact(record, 1L, "CREATE", now, operatorId);
            if (item.repairSourceBusinessId() != null) {
                repairSources.reserve(item.repairSourceBusinessId(), item.quantity());
                repairSources.recordFact(
                        item.repairSourceBusinessId(), "RESERVED", item.quantity(),
                        taskBusinessId, item.taskItemBusinessId(), now, operatorId);
            } else {
                sourceReservations.reserve(item.productionSourceBusinessId(), item.quantity());
            }
            sourceReservations.upsertActiveHold(new SourceReservationRepository.SourceHold(
                    taskBusinessId, item.taskItemBusinessId(), item.productionSourceBusinessId(),
                    item.repairSourceBusinessId(), item.quantity(), "ACTIVE"));
        }
        tasks.insertAssignmentFact(
                taskBusinessId, 1L, "CREATE", null,
                bundle.subjectSnapshot().snapshotBusinessId(), null,
                command.workDate(), null,
                command.processCode() == null ? null : command.processCode().name(),
                null, task.plannedWorkQuantity(), now, operatorId);

        saveAssessment(assessmentBusinessId, taskBusinessId, 1L, planned, assessed, bundle,
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
        return otherTaskRecord(command.otherTask(), taskBusinessId);
    }

    private OtherTaskRepository.OtherTaskRecord otherTaskRecord(
            ArrangeTaskCommand.OtherTaskRequest request, String taskBusinessId) {
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
            if (request.repairSourceBusinessId() != null) {
                requireRepairTask(command.taskType());
                if (!seenSources.add(request.repairSourceBusinessId())) {
                    throw new ApiException(
                            ApiErrorType.VALIDATION, "PRODUCTION_TASK_INVALID", "同一任务不能重复同一来源明细");
                }
                items.add(planRepairItem(
                        command.taskType(), command.processCode(), request.repairSourceBusinessId(),
                        "TI-" + UUID.randomUUID(), itemOrder++, request.plannedQuantity()));
                continue;
            }
            SourceRecord source = production.findSource(request.productionSourceBusinessId())
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.NOT_FOUND, "PRODUCTION_SOURCE_NOT_FOUND",
                            "生产来源不存在：" + request.productionSourceBusinessId()));
            DemandRecord demand = production.findDemand(source.demandBusinessId())
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.NOT_FOUND, "PRODUCTION_DEMAND_NOT_FOUND", "生产需求不存在"));

            requireTaskTypeConsistency(command.taskType(), command.processCode(), demand.demandType());
            requireRepairSourceBinding(command.taskType(), demand.demandType());
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

    private PlannedItem planRepairItem(
            TaskType taskType, TargetProcess processCode, String repairSourceBusinessId,
            String taskItemBusinessId, int itemOrder, int plannedQuantity) {
        requireRepairTask(taskType);
        RepairSourceRepository.RepairSourceRecord repair = repairSources.find(repairSourceBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_REPAIR_SOURCE_NOT_FOUND",
                        "返工来源不存在：" + repairSourceBusinessId));
        if (!"ACTIVE".equals(repair.status())) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_REPAIR_SOURCE_NOT_AVAILABLE",
                    "返工来源已失效，不能安排任务：" + repairSourceBusinessId);
        }
        TargetProcess repairProcess = TargetProcess.valueOf(repair.repairProcessCode());
        if (processCode != repairProcess) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_TASK_TYPE_INVALID",
                    "返工任务工序必须与返工来源的修复工序一致：" + repairProcess);
        }
        SourceRecord source = production.findSource(repair.productionSourceBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_SOURCE_NOT_FOUND", "返工来源缺少履约生产来源"));
        DemandRecord demand = production.findDemand(repair.demandBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_DEMAND_NOT_FOUND", "返工来源缺少生产需求"));
        requireTaskTypeConsistency(taskType, processCode, demand.demandType());
        requireTargetReachable(source.targetProcess(), processCode);
        return new PlannedItem(
                taskItemBusinessId, itemOrder, demand.demandBusinessId(), demand.demandType(),
                source.productionSourceBusinessId(), repair.physicalRootSourceBusinessId(),
                repair.repairSourceBusinessId(), demand.orderBusinessId(),
                demand.orderItemBusinessId(), demand.productBusinessId(),
                demand.productSnapshotRef(), demand.schemeBusinessId(), demand.schemeSnapshotRef(),
                source.targetProcess(), processCode, plannedQuantity,
                standardMinutesOf(demand, processCode));
    }

    private static void requireRepairTask(TaskType taskType) {
        if (taskType != TaskType.REPAIR) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_TASK_TYPE_INVALID",
                    "只有返工任务可以关联返工来源");
        }
    }

    private static void requireRepairSourceBinding(TaskType taskType, DemandType demandType) {
        if (taskType == TaskType.REPAIR && demandType != DemandType.RETURNED_PRODUCT_REPAIR) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_TASK_TYPE_INVALID",
                    "返工任务必须关联返工来源，不能直接用生产来源安排");
        }
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
        if (item.repairSourceBusinessId() != null) {
            RepairSourceRepository.RepairSourceRecord repair =
                    repairSources.lock(item.repairSourceBusinessId());
            if (!"ACTIVE".equals(repair.status())) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_REPAIR_SOURCE_NOT_AVAILABLE",
                        "返工来源已失效，不能安排任务：" + item.repairSourceBusinessId());
            }
            int available = repair.availableQuantity() - repair.reservedQuantity();
            if (available < item.quantity()) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_REPAIR_SOURCE_INSUFFICIENT",
                        "返工来源可安排数量不足：需要 " + item.quantity() + "，可用 " + available);
            }
            return;
        }
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
            TaskType taskType, SubjectRef subject, Integer enteredStandardMinutes,
            List<PlannedItem> planned, AssignmentSubjectSnapshotBundle bundle,
            Map<String, DailyLoadService.DayLoad> loads) {
        int dailyMinutes = bundle.dailyEffectiveMinutes();
        List<WorkloadAssessor.DetailStandard> standards = planned.stream()
                .map(item -> new WorkloadAssessor.DetailStandard(item.quantity(), item.standardMinutes()))
                .toList();

        if (subject.subjectType() == SubjectType.TEAM) {
            List<Long> memberOccupied = new ArrayList<>();
            for (var member : bundle.subjectView().members()) {
                memberOccupied.add(occupiedOf(loads.get(member.employeeBusinessId())));
            }
            long occupiedBefore = memberOccupied.stream().mapToLong(Long::longValue).min().orElse(0L);
            return WorkloadAssessor.normalise(workloadAssessor.assessTeam(
                    taskType, standards, enteredStandardMinutes, dailyMinutes,
                    occupiedBefore, bundle.subjectView().efficiencyFactor(), memberOccupied));
        }
        String employeeId = subject.subjectBusinessId();
        return WorkloadAssessor.normalise(workloadAssessor.assessIndividual(
                taskType, standards, enteredStandardMinutes, dailyMinutes,
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
        if (comparison == WorkloadComparison.OVER_STANDARD
                && !taskType.allowsOverStandard()) {
            if (overrideReason == null || overrideReason.isBlank()) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_WORKLOAD_OVER_STANDARD",
                        "超出每日有效工作时间，需要覆盖权限并填写原因");
            }
            authorization.require(
                    PermissionCodes.PRODUCTION_OVERRIDE_OVER_STANDARD, ResourceScope.GLOBAL);
        }
        if (!workloadAssessor.permits(taskType, comparison, overrideReason)) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_WORKLOAD_OVER_STANDARD",
                    "超出每日有效工作时间，需要覆盖权限并填写原因");
        }
    }

    private void applySlotsAndModes(ArrangeTaskCommand command, Set<String> participants) {
        validateSlotsFor(command.taskType(), participants, command.workDate(), null);
    }

    private void validateSlotsFor(
            TaskType taskType, Set<String> participants, LocalDate workDate, String ignoredTaskId) {
        boolean otherTask = taskType == TaskType.OTHER_TASK;
        String slotType = slotTypeOf(taskType);

        for (String employeeId : participants) {
            List<DailyLoadRepository.SlotRecord> existing =
                    dailyLoads.findSlots(employeeId, workDate).stream()
                            .filter(slot -> ignoredTaskId == null
                                    || !ignoredTaskId.equals(slot.taskBusinessId()))
                            .toList();
            String mode = dailyLoads.findWorkMode(employeeId, workDate).orElse(null);

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
            if (taskType.occupiesUniqueSlot() && slotOccupied) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_SLOT_CONFLICT",
                        "员工当日该类型任务槽位已被占用：" + slotType);
            }
            if (taskType.requiresNormalProduction()) {
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

    private Set<String> participantsOf(SubjectRef subject, AssignmentSubjectSnapshotBundle bundle) {
        Set<String> participants = new LinkedHashSet<>();
        for (AssignmentSubjectSnapshotView.MemberSnapshot member : bundle.subjectView().members()) {
            participants.add(member.employeeBusinessId());
        }
        if (participants.isEmpty() && subject.subjectType() == SubjectType.EMPLOYEE) {
            participants.add(subject.subjectBusinessId());
        }
        return participants;
    }

    private void saveAssessment(
            String assessmentBusinessId, String taskBusinessId, long assignmentVersion,
            List<PlannedItem> planned,
            WorkloadAssessor.AssignableResult assessed, AssignmentSubjectSnapshotBundle bundle,
            Set<String> participants, String overrideReason) {
        assessments.insertAssessment(new AssessmentRepository.AssessmentRecord(
                assessmentBusinessId, taskBusinessId, assignmentVersion, assessed.requiredSeconds(),
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

        TaskItemRecord toRecord(String taskBusinessId, long assignmentVersion) {
            return new TaskItemRecord(
                    taskItemBusinessId, taskBusinessId, itemOrder, demandBusinessId, demandType,
                    productionSourceBusinessId, physicalRootSourceBusinessId, repairSourceBusinessId,
                    orderBusinessId, orderItemBusinessId, productBusinessId, productSnapshotRef,
                    schemeBusinessId, schemeSnapshotRef, targetProcess, quantity, standardMinutes,
                    "PENDING", assignmentVersion, 1L);
        }
    }

    /** 调整时同时锁定的新旧产能键。 */
    private record CapacityKey(String productBusinessId, LocalDate workDate) {
    }

    /** 调整时同时锁定的新旧员工日期负载键。 */
    private record DayKey(String employeeBusinessId, LocalDate workDate) {
    }
}

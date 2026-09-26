package com.yumi.production.overtime;

import com.yumi.catalog.employee.dto.EmployeeSnapshot;
import com.yumi.catalog.employee.service.EmployeeEligibilityService;
import com.yumi.identity.AuditContext;
import com.yumi.production.ProductionNodes;
import com.yumi.production.overtime.internal.OvertimeTaskRepository;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import com.yumi.shared.numbering.SequenceAllocator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 超额任务应用服务（任务 5.14，施工文档 §3.7/§6.5/§8/§11/§12）。
 *
 * 口径：
 * <ul>
 *   <li>只在**执行当天**创建（服务端业务日期 `LocalDate.now()`，不信任客户端「今天」）；</li>
 *   <li>来源只能是**未来日期**的 `NORMAL` 任务明细（`task_date > 今天`、`task_type = 'NORMAL'`、
 *       明细 `PENDING` 且 `source_type = 'ORDER'`）；订单、产品、工序全部由服务端从未来明细解析；</li>
 *   <li>预占校验 `可用 = 未来明细计划数量 − 有效预占合计`，按未来明细 id 升序加锁，并发不超支；
 *       **预占不修改未来明细原计划数量、不产生工序流入或完成事实**；</li>
 *   <li>核验遵守普通等式 `completed = qualified + rework + scrap` 且 `completed ≤ planned`，
 *       每条明细只能核验一次；核验后释放本任务全部有效预占，
 *       只有**合格数量**按预占顺序形成「计划待调整」提醒（建议数量分摊不超过各预占数量），
 *       返工、报废与未完成不减少未来计划、不写未来明细。</li>
 * </ul>
 * 超额任务不写 `production_verifications`、`fulfillment_entries`、`inventory_movements` 或任何订单侧投影。
 */
@Service
public class OvertimeTaskService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String TASK_TYPE_NORMAL = "NORMAL";
    private static final String SOURCE_TYPE_ORDER = "ORDER";

    private static final String HANDLING_SUPERSEDED = "SUPERSEDED";
    private static final String HANDLING_NO_ADJUSTMENT = "NO_ADJUSTMENT";
    private static final String RELEASE_REASON = "超额任务核验完成，预占结算";
    private static final String SUPERSEDED_REASON = "核验合格，已转入计划待调整";
    private static final String NO_ADJUSTMENT_REASON = "零合格，无需调整";
    private static final String SEQUENCE_KEY = "overtime_tasks";

    private final OvertimeTaskRepository repository;
    private final EmployeeEligibilityService eligibility;
    private final SequenceAllocator sequenceAllocator;
    private final AuditContext auditContext;

    public OvertimeTaskService(OvertimeTaskRepository repository, EmployeeEligibilityService eligibility,
                               SequenceAllocator sequenceAllocator, AuditContext auditContext) {
        this.repository = repository;
        this.eligibility = eligibility;
        this.sequenceAllocator = sequenceAllocator;
        this.auditContext = auditContext;
    }

    public OvertimeTaskViews.OvertimeTaskView get(long id) {
        var task = repository.findTaskById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "超额任务不存在"));
        return toView(task);
    }

    /** 按执行日期列出超额任务（工作台提醒读取）。 */
    public List<OvertimeTaskViews.OvertimeTaskView> list(LocalDate taskDate) {
        return repository.findTasks(taskDate).stream().map(this::toView).toList();
    }

    /** 创建超额任务：当天、未来正常明细来源、员工在职且具备该工序工种资格、预占不超未预占余额。 */
    @Transactional
    public OvertimeTaskViews.OvertimeTaskView create(OvertimeTaskViews.CreateOvertimeTaskRequest request) {
        var today = LocalDate.now();
        if (request.taskDate() == null || !today.equals(request.taskDate())) {
            throw new ApiException(ErrorCode.OVERTIME_DATE_INVALID, "超额任务只能在执行当天创建",
                    List.of(new ApiFieldError("taskDate", "执行日期必须是今天 " + today)));
        }
        var errors = new ArrayList<ApiFieldError>();
        if (request.employeeId() == null) {
            errors.add(new ApiFieldError("employeeId", "执行员工必填"));
        }
        if (request.items() == null || request.items().isEmpty()) {
            errors.add(new ApiFieldError("items", "至少一条来源明细"));
        } else {
            for (int index = 0; index < request.items().size(); index++) {
                var item = request.items().get(index);
                var prefix = "items[" + index + "]";
                if (item == null || item.futureTaskItemId() == null) {
                    errors.add(new ApiFieldError(prefix + ".futureTaskItemId", "来源未来任务明细必填"));
                }
                if (item == null || item.plannedQuantity() == null || item.plannedQuantity() < 1) {
                    errors.add(new ApiFieldError(prefix + ".plannedQuantity", "预占数量必须大于 0"));
                }
            }
        }
        failIfInvalid(errors);

        var items = request.items();
        // 锁定顺序：未来任务明细按 id 升序加锁 → 有效预占按 id 升序加锁。
        var futureItemIds = items.stream().map(OvertimeTaskViews.CreateOvertimeTaskRequest.CreateOvertimeItemRequest
                ::futureTaskItemId).distinct().sorted().toList();
        var futureItems = new LinkedHashMap<Long, OvertimeTaskRepository.FutureItemRow>();
        for (var row : repository.lockFutureItems(futureItemIds)) {
            futureItems.put(row.id(), row);
        }
        var taskInfo = repository.findFutureTaskInfo(futureItems.values().stream()
                .map(OvertimeTaskRepository.FutureItemRow::taskId).distinct().sorted().toList());

        for (int index = 0; index < items.size(); index++) {
            var prefix = "items[" + index + "]";
            var future = futureItems.get(items.get(index).futureTaskItemId());
            if (future == null) {
                errors.add(new ApiFieldError(prefix + ".futureTaskItemId", "来源未来任务明细不存在"));
                continue;
            }
            var info = taskInfo.get(future.taskId());
            if (info == null || !TASK_TYPE_NORMAL.equals(info.taskType())) {
                errors.add(new ApiFieldError(prefix + ".futureTaskItemId", "来源必须是未来日期的正常任务明细"));
                continue;
            }
            if (!info.taskDate().isAfter(today)) {
                errors.add(new ApiFieldError(prefix + ".futureTaskItemId",
                        "来源任务日期 " + info.taskDate() + " 必须晚于今天 " + today));
                continue;
            }
            if (!STATUS_PENDING.equals(future.status())) {
                errors.add(new ApiFieldError(prefix + ".futureTaskItemId", "来源明细必须是待执行状态"));
                continue;
            }
            if (!SOURCE_TYPE_ORDER.equals(future.sourceType())) {
                errors.add(new ApiFieldError(prefix + ".futureTaskItemId", "来源明细必须是订单需求来源"));
            }
        }
        failIfInvalid(errors);

        // 订单、产品、工序一律由未来明细解析；一个超额任务只承载同一工序的明细（任务头只有一个工种）。
        var node = futureItems.get(items.get(0).futureTaskItemId()).node();
        if (!ProductionNodes.isNode(node)) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "来源明细工序不合法",
                    List.of(new ApiFieldError("items[0].futureTaskItemId", "工序 " + node + " 不是有效工序")));
        }
        for (int index = 1; index < items.size(); index++) {
            var future = futureItems.get(items.get(index).futureTaskItemId());
            if (!node.equals(future.node())) {
                throw new ApiException(ErrorCode.VALIDATION_INVALID, "同一超额任务只能包含同一工序的明细",
                        List.of(new ApiFieldError("items[" + index + "].futureTaskItemId",
                                "工序 " + future.node() + " 与 " + node + " 不一致")));
            }
        }

        // 在锁内重算未预占余额：可用 = 未来明细计划数量 − 有效预占合计（本次请求内同一未来明细累加）。
        var activeByFutureItem = new HashMap<Long, Integer>();
        for (var preemption : repository.lockActivePreemptions(futureItemIds)) {
            activeByFutureItem.merge(preemption.futureTaskItemId(), preemption.preemptedQuantity(), Integer::sum);
        }
        var requestedByFutureItem = new HashMap<Long, Integer>();
        for (int index = 0; index < items.size(); index++) {
            var item = items.get(index);
            var future = futureItems.get(item.futureTaskItemId());
            int available = future.plannedQuantity() - activeByFutureItem.getOrDefault(future.id(), 0)
                    - requestedByFutureItem.getOrDefault(future.id(), 0);
            if (item.plannedQuantity() > available) {
                throw new ApiException(ErrorCode.OVERTIME_RESERVATION_EXCEEDED, "预占超过未来明细未预占余额",
                        List.of(new ApiFieldError("items[" + index + "].plannedQuantity",
                                "未来明细可预占 " + available + "，本次预占 " + item.plannedQuantity())));
            }
            requestedByFutureItem.merge(future.id(), item.plannedQuantity(), Integer::sum);
        }

        var employee = requireEligible(request.employeeId(), node);
        var workType = repository.findWorkTypeByCode(node)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_INVALID, "工序对应的工种不存在",
                        List.of(new ApiFieldError("items[0].futureTaskItemId", "工序 " + node + " 没有对应工种"))));
        var audit = auditContext.current();
        var taskNo = SequenceAllocator.format("OT", sequenceAllocator.next(SEQUENCE_KEY), 6);
        long taskId = repository.insertTask(taskNo, request.taskDate(), request.employeeId(), employee.name(),
                workType.id(), workType.name(), request.note(), audit.requestId(), audit.idempotencyKey());

        for (int index = 0; index < items.size(); index++) {
            var item = items.get(index);
            var future = futureItems.get(item.futureTaskItemId());
            long overtimeItemId = repository.insertItem(taskId, index + 1, future.orderId(), future.orderItemId(),
                    future.productId(), future.productNo(), future.productName(), node, item.plannedQuantity(),
                    audit.requestId(), audit.idempotencyKey());
            long preemptionId = repository.insertPreemption(overtimeItemId, future.id(), future.orderId(),
                    future.orderItemId(), node, item.plannedQuantity(), audit.requestId(), audit.idempotencyKey());
            // 提醒只是工作台提示，不是数量事实；一条超额明细一条「超额待核验」提醒。
            repository.insertPendingVerifyReminder(future.orderId(), future.orderItemId(), node, preemptionId,
                    future.id(), item.plannedQuantity(), audit.requestId(), audit.idempotencyKey());
        }
        return get(taskId);
    }

    /**
     * 核验超额任务：等式与上限校验、一次性核验、释放本任务全部有效预占、
     * 结束待核验提醒，并按预占顺序把**合格数量**分摊为未来计划调整建议。
     */
    @Transactional
    public OvertimeTaskViews.OvertimeTaskView verify(long id, OvertimeTaskViews.VerifyOvertimeTaskRequest request) {
        repository.findTaskByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "超额任务不存在"));
        var stored = repository.findItemsByTaskForUpdate(id);
        var storedById = new LinkedHashMap<Long, OvertimeTaskRepository.OvertimeItemRow>();
        for (var row : stored) {
            storedById.put(row.id(), row);
        }

        var errors = new ArrayList<ApiFieldError>();
        var submitted = new HashMap<Long, Integer>();
        if (request.items() == null || request.items().isEmpty()) {
            errors.add(new ApiFieldError("items", "至少一条核验明细"));
        } else {
            for (int index = 0; index < request.items().size(); index++) {
                var item = request.items().get(index);
                var prefix = "items[" + index + "]";
                if (item == null || item.overtimeItemId() == null) {
                    errors.add(new ApiFieldError(prefix + ".overtimeItemId", "超额明细必填"));
                    continue;
                }
                if (!storedById.containsKey(item.overtimeItemId())) {
                    errors.add(new ApiFieldError(prefix + ".overtimeItemId", "明细不属于该超额任务"));
                    continue;
                }
                if (submitted.put(item.overtimeItemId(), index) != null) {
                    errors.add(new ApiFieldError(prefix + ".overtimeItemId", "同一明细重复提交"));
                    continue;
                }
                requireNonNegative(item.completedQuantity(), prefix + ".completedQuantity", errors);
                requireNonNegative(item.qualifiedQuantity(), prefix + ".qualifiedQuantity", errors);
                requireNonNegative(item.reworkQuantity(), prefix + ".reworkQuantity", errors);
                requireNonNegative(item.scrapQuantity(), prefix + ".scrapQuantity", errors);
            }
        }
        failIfInvalid(errors);

        // 一次性核验：状态与核验列共同兜底，第二次提交返回 STATE_ALREADY_VERIFIED。
        for (int index = 0; index < request.items().size(); index++) {
            var item = request.items().get(index);
            var row = storedById.get(item.overtimeItemId());
            if (!STATUS_PENDING.equals(row.status()) || row.verifiedAt() != null
                    || row.qualifiedQuantity() != null) {
                throw new ApiException(ErrorCode.STATE_ALREADY_VERIFIED, "超额明细已核验，不可重复核验",
                        List.of(new ApiFieldError("items[" + index + "].overtimeItemId", "明细已核验")));
            }
            requireEquation(index, item, row.plannedQuantity());
        }

        var audit = auditContext.current();
        int qualifiedTotal = 0;
        for (int index = 0; index < request.items().size(); index++) {
            var item = request.items().get(index);
            var row = storedById.get(item.overtimeItemId());
            int incomplete = row.plannedQuantity() - item.completedQuantity();
            int updated = repository.markItemVerified(item.overtimeItemId(), item.completedQuantity(),
                    item.qualifiedQuantity(), item.reworkQuantity(), item.scrapQuantity(), incomplete, item.note(),
                    audit.adminUsername(), audit.requestId(), audit.idempotencyKey());
            if (updated == 0) {
                // 状态条件未命中：另一事务已完成核验（一次性核验由状态列与核验列共同兜底）。
                throw new ApiException(ErrorCode.STATE_ALREADY_VERIFIED, "超额明细已核验，不可重复核验",
                        List.of(new ApiFieldError("items[" + index + "].overtimeItemId", "明细已核验")));
            }
            qualifiedTotal += item.qualifiedQuantity();
        }

        // 核验后释放本任务全部仍有效的预占；预占不修改未来明细原计划数量。
        var preemptions = repository.findPreemptionsByTaskForUpdate(id);
        repository.releaseActivePreemptions(id, RELEASE_REASON, audit.adminUsername(), audit.requestId());
        var preemptionIds = preemptions.stream().map(OvertimeTaskRepository.OvertimePreemptionRow::id).toList();
        repository.closeOpenPendingVerifyReminders(preemptionIds,
                qualifiedTotal > 0 ? HANDLING_SUPERSEDED : HANDLING_NO_ADJUSTMENT,
                qualifiedTotal > 0 ? SUPERSEDED_REASON : NO_ADJUSTMENT_REASON, audit.adminUsername(),
                audit.requestId(), audit.idempotencyKey());

        // 只有合格数量按预占 id 顺序形成「计划待调整」建议，单条不超过该预占数量，合计不超过合格总数。
        int remaining = qualifiedTotal;
        for (var preemption : preemptions) {
            if (remaining <= 0) {
                break;
            }
            int suggestion = Math.min(preemption.preemptedQuantity(), remaining);
            repository.insertPlanAdjustmentReminder(preemption.orderId(), preemption.orderItemId(),
                    preemption.node(), preemption.id(), preemption.futureTaskItemId(), suggestion,
                    audit.requestId(), audit.idempotencyKey());
            remaining -= suggestion;
        }
        return get(id);
    }

    /** 等式 `completed = qualified + rework + scrap` 且 `completed ≤ planned`。 */
    private static void requireEquation(int index, OvertimeTaskViews.VerifyOvertimeTaskRequest.VerifyOvertimeItemRequest
            item, int plannedQuantity) {
        var prefix = "items[" + index + "]";
        int completed = item.completedQuantity();
        int qualified = item.qualifiedQuantity();
        int rework = item.reworkQuantity();
        int scrap = item.scrapQuantity();
        if (completed != qualified + rework + scrap) {
            throw new ApiException(ErrorCode.VERIFICATION_EQUATION_INVALID, "本次完成必须等于合格 + 返工 + 报废",
                    List.of(new ApiFieldError(prefix + ".completedQuantity",
                            "完成 " + completed + " ≠ 合格 " + qualified + " + 返工 " + rework + " + 报废 " + scrap)));
        }
        if (completed > plannedQuantity) {
            throw new ApiException(ErrorCode.VERIFICATION_EQUATION_INVALID, "本次完成不得超过计划数量",
                    List.of(new ApiFieldError(prefix + ".completedQuantity",
                            "计划 " + plannedQuantity + "，本次完成 " + completed)));
        }
    }

    /** 员工必须**在职且具备该工序工种资格**；任何失败都映射为 `EMPLOYEE_NOT_ELIGIBLE`。 */
    private EmployeeSnapshot requireEligible(long employeeId, String node) {
        try {
            return eligibility.checkEligible(employeeId, node);
        } catch (ApiException failure) {
            throw new ApiException(ErrorCode.EMPLOYEE_NOT_ELIGIBLE, "员工不在职或不具备该工序工种资格",
                    List.of(new ApiFieldError("employeeId",
                            failure.getMessage() == null ? "员工不具备执行资格" : failure.getMessage())));
        }
    }

    private static void requireNonNegative(Integer value, String field, List<ApiFieldError> errors) {
        if (value == null || value < 0) {
            errors.add(new ApiFieldError(field, "数量必填且不得为负"));
        }
    }

    private static void failIfInvalid(List<ApiFieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID,
                    ErrorCode.VALIDATION_INVALID.defaultMessage(), errors);
        }
    }

    private OvertimeTaskViews.OvertimeTaskView toView(OvertimeTaskRepository.OvertimeTaskRow task) {
        var items = repository.findItemsByTask(task.id()).stream()
                .map(row -> new OvertimeTaskViews.OvertimeItemView(row.id(), row.itemNo(), row.orderId(),
                        row.orderItemId(), row.productId(), row.productNo(), row.productName(), row.node(),
                        row.plannedQuantity(), row.status(), row.completedQuantity(), row.qualifiedQuantity(),
                        row.reworkQuantity(), row.scrapQuantity(), row.incompleteQuantity(), row.verifyNote(),
                        row.verifiedBy(), row.verifiedAt(), row.version()))
                .toList();
        var preemptions = repository.findPreemptionsByTask(task.id());
        var preemptionViews = preemptions.stream()
                .map(row -> new OvertimeTaskViews.OvertimePreemptionView(row.id(), row.overtimeTaskItemId(),
                        row.futureTaskItemId(), row.orderId(), row.orderItemId(), row.node(),
                        row.preemptedQuantity(), row.status(), row.releasedAt(), row.releasedBy(),
                        row.releaseReason()))
                .toList();
        var reminders = repository.findRemindersByPreemptions(
                        preemptions.stream().map(OvertimeTaskRepository.OvertimePreemptionRow::id).toList())
                .stream()
                .map(row -> new OvertimeTaskViews.OvertimeReminderView(row.id(), row.reminderType(), row.orderId(),
                        row.orderItemId(), row.node(), row.preemptionId(), row.futureTaskItemId(), row.quantity(),
                        row.status(), row.handlingType(), row.reason()))
                .toList();
        return new OvertimeTaskViews.OvertimeTaskView(task.id(), task.taskNo(), task.taskDate(), task.employeeId(),
                task.employeeNameSnapshot(), workTypeCode(task.workTypeId()), task.workTypeNameSnapshot(),
                task.note(), task.version(), items, preemptionViews, reminders);
    }

    /** 工种 code 即工序 code；任务头只存 id，读模型按 code 对外。 */
    private String workTypeCode(long workTypeId) {
        return repository.findWorkTypeById(workTypeId).map(OvertimeTaskRepository.WorkTypeRow::code)
                .orElse(null);
    }
}

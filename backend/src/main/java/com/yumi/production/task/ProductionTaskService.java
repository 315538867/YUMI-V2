package com.yumi.production.task;

import com.yumi.calculation.production.ProductionTimeCalculator;
import com.yumi.calculation.production.ProductionTimeResult;
import com.yumi.calculation.production.ProductionWorkType;
import com.yumi.catalog.employee.service.EmployeeEligibilityService;
import com.yumi.identity.AuditContext;
import com.yumi.orders.ledger.FulfillmentLedger;
import com.yumi.production.ProductionNodes;
import com.yumi.production.ProductionSnapshotReader;
import com.yumi.production.capacity.ProductionCapacityService;
import com.yumi.production.internal.OrderProductionReference;
import com.yumi.production.scrap.internal.ProductionQuantityReturnRepository;
import com.yumi.production.source.internal.ReworkSourceRepository;
import com.yumi.production.task.internal.ProductionFactRepository;
import com.yumi.production.task.internal.ProductionTaskItemRow;
import com.yumi.production.task.internal.ProductionTaskRepository;
import com.yumi.production.task.internal.ProductionTaskRow;
import com.yumi.production.verification.internal.ProductionVerificationRepository;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import com.yumi.shared.numbering.SequenceAllocator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 生产任务应用服务（阶段五 5.2/5.4/5.5）：一个任务头 + 多条任务明细的创建、查询与明细取消。
 *
 * <p>创建口径：任务头只保存共同组织信息；每条明细绑定一个已确认订单明细、一个产品、一个工序、一个来源边界
 * 和计划数量，产品/订单/工序/标准分钟/能力参数全部由服务端从订单确认快照解析，客户端派生值一律忽略。
 *
 * <p>创建顺序（锁定顺序见 `production-module-design.md` §11.2）：
 * 员工资格 → 按 orderItemId 升序锁订单履约余额 → 按来源 id 升序锁返工来源/数量回转 → 锁产品日期产能 → 写任务。
 * 任一明细失败整批回滚，不留下任务头、明细、产能占用或来源安排。
 */
@Service
public class ProductionTaskService {

    static final String TYPE_NORMAL = "NORMAL";
    static final String TYPE_REWORK = "REWORK";
    static final String SOURCE_ORDER = "ORDER";
    static final String SOURCE_REWORK = "REWORK_SOURCE";
    static final String SOURCE_RETURN = "QUANTITY_RETURN";
    static final String SOURCE_AFTER_SALES = "AFTER_SALES_SOURCE";
    static final String STATUS_PENDING = "PENDING";
    static final String ORDER_CONFIRMED = "CONFIRMED";

    private final ProductionTaskRepository repository;
    private final ProductionCapacityService capacityService;
    private final OrderProductionReference reference;
    private final ProductionSnapshotReader snapshotReader;
    private final EmployeeEligibilityService eligibility;
    private final ReworkSourceRepository reworkSourceRepository;
    private final ProductionQuantityReturnRepository returnRepository;
    private final com.yumi.production.aftersales.internal.AfterSalesProductionSourceRepository
            afterSalesSourceRepository;
    private final FulfillmentLedger ledger;
    private final ProductionVerificationRepository verificationRepository;
    private final ProductionFactRepository factRepository;
    private final SequenceAllocator sequenceAllocator;
    private final AuditContext auditContext;

    public ProductionTaskService(ProductionTaskRepository repository,
                                 ProductionCapacityService capacityService,
                                 OrderProductionReference reference,
                                 ProductionSnapshotReader snapshotReader,
                                 EmployeeEligibilityService eligibility,
                                 ReworkSourceRepository reworkSourceRepository,
                                 ProductionQuantityReturnRepository returnRepository,
                                 com.yumi.production.aftersales.internal.AfterSalesProductionSourceRepository
                                         afterSalesSourceRepository,
                                 FulfillmentLedger ledger,
                                 ProductionVerificationRepository verificationRepository,
                                 ProductionFactRepository factRepository,
                                 SequenceAllocator sequenceAllocator,
                                 AuditContext auditContext) {
        this.repository = repository;
        this.capacityService = capacityService;
        this.reference = reference;
        this.snapshotReader = snapshotReader;
        this.eligibility = eligibility;
        this.reworkSourceRepository = reworkSourceRepository;
        this.returnRepository = returnRepository;
        this.afterSalesSourceRepository = afterSalesSourceRepository;
        this.ledger = ledger;
        this.verificationRepository = verificationRepository;
        this.factRepository = factRepository;
        this.sequenceAllocator = sequenceAllocator;
        this.auditContext = auditContext;
    }

    /** 新建任务入参：头共同信息 + 明细数组；明细的产品、订单、工序与标准分钟一律服务端解析。 */
    public record CreateRequest(
            LocalDate taskDate,
            Long employeeId,
            Long workTypeId,
            String taskType,
            String note,
            List<ItemRequest> items) {
    }

    /** 明细入参：只提交订单明细、计划数量与来源；不提交产品、工序、标准分钟或派生数量。 */
    public record ItemRequest(
            Long orderItemId,
            Integer plannedQuantity,
            String sourceType,
            Long sourceId) {
    }

    // ---------- 创建 ----------

    @Transactional
    public ProductionTaskViews.TaskView create(CreateRequest request) {
        var errors = new ArrayList<ApiFieldError>();
        if (request.taskDate() == null) {
            errors.add(new ApiFieldError("taskDate", "任务日期必填"));
        }
        if (request.employeeId() == null) {
            errors.add(new ApiFieldError("employeeId", "执行员工必填"));
        }
        if (request.workTypeId() == null) {
            errors.add(new ApiFieldError("workTypeId", "工序必填"));
        }
        var taskType = requireTaskType(request.taskType(), errors);
        if (request.items() == null || request.items().isEmpty()) {
            errors.add(new ApiFieldError("items", "至少一条任务明细"));
        }
        if (!errors.isEmpty()) {
            failIfInvalid(errors);
        }
        var workTypeCode = workTypeCode(request.workTypeId(), errors);
        failIfInvalid(errors);
        var employee = eligibility.checkEligible(request.employeeId(), workTypeCode);
        var audit = auditContext.current();

        var planned = planItems(request, workTypeCode, errors);
        failIfInvalid(errors);

        var taskNo = SequenceAllocator.format("PT", sequenceAllocator.next("production_tasks"), 6);
        var header = new ProductionTaskRow(null, taskNo, request.taskDate(), request.employeeId(),
                employee.name(), request.workTypeId(), workTypeName(workTypeCode), taskType,
                request.note(), 0L);
        long taskId = repository.insertTask(header, audit.requestId(), audit.idempotencyKey());

        int itemNo = 0;
        for (var item : planned) {
            itemNo++;
            repository.insertItem(item.toRow(taskId, itemNo), audit.requestId(), audit.idempotencyKey());
            applySourceOccupation(item, audit);
        }
        repository.bumpTaskVersion(taskId, audit.requestId());
        return get(taskId);
    }

    /** 逐条解析并校验明细；锁定顺序在解析过程中按固定次序执行。 */
    private List<PlannedItem> planItems(CreateRequest request, String workTypeCode,
                                        List<ApiFieldError> errors) {
        var items = request.items();
        var orderedIds = new ArrayList<Long>();
        for (var item : items) {
            if (item.orderItemId() != null) {
                orderedIds.add(item.orderItemId());
            }
        }
        orderedIds.sort(Long::compareTo);
        for (var orderItemId : orderedIds) {
            ledger.lockBalance(orderItemId);
        }

        var planned = new ArrayList<PlannedItem>();
        var capacityUsage = new LinkedHashMap<String, Integer>();
        for (int index = 0; index < items.size(); index++) {
            var item = items.get(index);
            var prefix = "items[" + index + "]";
            var plannedItem = planItem(request, item, prefix, workTypeCode, capacityUsage, errors);
            if (plannedItem != null) {
                planned.add(plannedItem);
            }
        }
        return planned;
    }

    private PlannedItem planItem(CreateRequest request, ItemRequest item, String prefix, String workTypeCode,
                                 Map<String, Integer> capacityUsage, List<ApiFieldError> errors) {
        if (item.orderItemId() == null) {
            errors.add(new ApiFieldError(prefix + ".orderItemId", "订单明细必填"));
            return null;
        }
        var context = reference.item(item.orderItemId());
        if (context == null) {
            errors.add(new ApiFieldError(prefix + ".orderItemId", "订单明细不存在"));
            return null;
        }
        if (!ORDER_CONFIRMED.equals(context.status())) {
            errors.add(new ApiFieldError(prefix + ".orderItemId", "仅已确认订单可以排产"));
            return null;
        }
        int quantity = item.plannedQuantity() == null ? 0 : item.plannedQuantity();
        if (quantity < 1) {
            errors.add(new ApiFieldError(prefix + ".plannedQuantity", "计划数量必须大于 0"));
            return null;
        }
        var sourceType = item.sourceType() == null || item.sourceType().isBlank()
                ? SOURCE_ORDER : item.sourceType().trim();
        var snapshot = snapshotReader.byOrderItem(item.orderItemId()).orElse(null);
        if (snapshot == null) {
            errors.add(new ApiFieldError(prefix + ".orderItemId", "订单明细缺少生产参数快照"));
            return null;
        }
        var node = workTypeCode;
        Integer standardMinutes = snapshot.standardMinutes(node);
        if (standardMinutes == null) {
            errors.add(new ApiFieldError(prefix + ".orderItemId", "该工序缺少标准分钟快照"));
            return null;
        }
        var workType = ProductionWorkType.fromNode(node);
        ProductionTimeResult time;
        long sourceId;
        switch (sourceType) {
            case SOURCE_ORDER -> {
                sourceId = item.orderItemId();
                if (!reserveCapacity(request, snapshot, node, quantity, prefix, capacityUsage, errors)) {
                    return null;
                }
                time = ProductionTimeCalculator.calculateNormal(quantity, standardMinutes, workType,
                        snapshot.makingEffectiveHourRate(), snapshot.workdayHours());
            }
            case SOURCE_RETURN -> {
                if (item.sourceId() == null) {
                    errors.add(new ApiFieldError(prefix + ".sourceId", "数量回转来源必填"));
                    return null;
                }
                var source = returnRepository.findByIdForUpdate(item.sourceId()).orElse(null);
                if (source == null) {
                    errors.add(new ApiFieldError(prefix + ".sourceId", "数量回转来源不存在"));
                    return null;
                }
                if (!node.equals(source.node()) || source.orderItemId() != item.orderItemId()) {
                    errors.add(new ApiFieldError(prefix + ".sourceId", "数量回转必须回到同一订单明细的同一工序"));
                    return null;
                }
                if (quantity > source.availableQuantity()) {
                    throw new ApiException(ErrorCode.SOURCE_INSUFFICIENT, "数量回转余额不足",
                            List.of(new ApiFieldError(prefix + ".plannedQuantity",
                                    "可分配 " + source.availableQuantity() + "，本次 " + quantity)));
                }
                sourceId = item.sourceId();
                if (!reserveCapacity(request, snapshot, node, quantity, prefix, capacityUsage, errors)) {
                    return null;
                }
                time = ProductionTimeCalculator.calculateNormal(quantity, standardMinutes, workType,
                        snapshot.makingEffectiveHourRate(), snapshot.workdayHours());
            }
            case SOURCE_REWORK -> {
                if (item.sourceId() == null) {
                    errors.add(new ApiFieldError(prefix + ".sourceId", "返工来源必填"));
                    return null;
                }
                var source = reworkSourceRepository.findByIdForUpdate(item.sourceId()).orElse(null);
                if (source == null) {
                    errors.add(new ApiFieldError(prefix + ".sourceId", "返工来源不存在"));
                    return null;
                }
                if (!node.equals(source.node()) || source.orderItemId() != item.orderItemId()) {
                    throw new ApiException(ErrorCode.SOURCE_INVALID, "返工必须在来源发生工序内进行",
                            List.of(new ApiFieldError(prefix + ".sourceId", "来源发生工序 " + source.node())));
                }
                if (quantity > source.availableQuantity()) {
                    throw new ApiException(ErrorCode.SOURCE_INSUFFICIENT, "返工来源余额不足",
                            List.of(new ApiFieldError(prefix + ".plannedQuantity",
                                    "可安排 " + source.availableQuantity() + "，本次 " + quantity)));
                }
                sourceId = item.sourceId();
                time = ProductionTimeCalculator.calculateRework(quantity, standardMinutes);
            }
            case SOURCE_AFTER_SALES -> {
                // 售后生产：来源由售后模块持有并在创建后自行安排；不占产品日产能、不登记订单侧计划占用
                if (item.sourceId() == null) {
                    errors.add(new ApiFieldError(prefix + ".sourceId", "售后生产来源必填"));
                    return null;
                }
                sourceId = item.sourceId();
                time = TYPE_REWORK.equals(request.taskType())
                        ? ProductionTimeCalculator.calculateRework(quantity, standardMinutes)
                        : ProductionTimeCalculator.calculateNormal(quantity, standardMinutes, workType,
                                snapshot.makingEffectiveHourRate(), snapshot.workdayHours());
            }
            default -> {
                errors.add(new ApiFieldError(prefix + ".sourceType", "来源类型不合法"));
                return null;
            }
        }
        return new PlannedItem(context, node, quantity, sourceType, sourceId, standardMinutes, time, snapshot);
    }

    /** 产品日产能硬约束：只有正常来源明细占用，按「产品 + 日期 + 工序」在锁内重算。 */
    private boolean reserveCapacity(CreateRequest request, ProductionSnapshotReader.ProductionSnapshot snapshot,
                                    String node, int quantity, String prefix,
                                    Map<String, Integer> capacityUsage, List<ApiFieldError> errors) {
        var key = snapshot.productId() + ":" + node;
        int used = capacityUsage.computeIfAbsent(key, ignored -> capacityService.lockedUsage(
                snapshot.productId(), request.taskDate(), node, snapshot.moldQuantity(),
                snapshot.dailyBatchLimit()).usedNormalQuantity());
        int dailyMax = snapshot.dailyMaxCapacity();
        if (used + quantity > dailyMax) {
            throw new ApiException(ErrorCode.CAPACITY_EXCEEDED, "超过产品当日最大产能",
                    List.of(new ApiFieldError(prefix + ".plannedQuantity",
                            "当日最大产能 " + dailyMax + "，已占用 " + used + "，本次 " + quantity)));
        }
        capacityUsage.put(key, used + quantity);
        return true;
    }

    /** 来源占用：正常明细登记计划占用；回转扣减可分配余额；返工增加来源已安排量；售后来源由售后模块安排。 */
    private void applySourceOccupation(PlannedItem item, AuditContext.Context audit) {
        if (SOURCE_ORDER.equals(item.sourceType())) {
            ledger.applyPlanned(item.context().orderItemId(), item.node(), item.quantity(), true,
                    audit.requestId());
            return;
        }
        if (SOURCE_RETURN.equals(item.sourceType())) {
            returnRepository.allocate(item.sourceId(), item.quantity(), audit.requestId());
            ledger.applyPlanned(item.context().orderItemId(), item.node(), item.quantity(), true,
                    audit.requestId());
            return;
        }
        if (SOURCE_AFTER_SALES.equals(item.sourceType())) {
            return;
        }
        reworkSourceRepository.arrange(item.sourceId(), item.quantity(), audit.requestId());
    }

    // ---------- 查询 ----------

    public List<ProductionTaskViews.TaskView> list(LocalDate dateFrom, LocalDate dateTo, Long employeeId,
                                                   Long workTypeId, String taskType, String status,
                                                   Long orderId, Long productId) {
        var tasks = repository.findTasks(dateFrom, dateTo, employeeId, workTypeId, taskType, orderId, productId);
        return toViews(tasks).stream()
                .filter(view -> status == null || status.isBlank() || status.equals(view.derivedStatus()))
                .toList();
    }

    public ProductionTaskViews.TaskView get(long id) {
        var task = repository.findTaskById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "生产任务不存在"));
        return toViews(List.of(task)).get(0);
    }

    /** 任务事实时间线（阶段五 5.18）：按 `factTime ASC, factType ASC, factId ASC` 返回不可变事实。 */
    public List<ProductionTaskViews.FactView> facts(long taskId) {
        repository.findTaskById(taskId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "生产任务不存在"));
        return factRepository.findByTask(taskId).stream()
                .map(row -> new ProductionTaskViews.FactView(
                        row.factTime() == null ? null : row.factTime().toString(), row.factType(),
                        row.factId(), row.node(), row.quantity(), row.orderItemId(), row.referenceId(),
                        row.operator(), row.reason(), row.note()))
                .toList();
    }

    private static ProductionTaskViews.VerificationFactView toVerificationView(
            ProductionVerificationRepository.VerificationRow row) {
        if (row == null) {
            return null;
        }
        return new ProductionTaskViews.VerificationFactView(row.plannedQuantity(), row.completedQuantity(),
                row.qualifiedQuantity(), row.reworkQuantity(), row.scrapQuantity(), row.incompleteQuantity(),
                row.verifyNote(), row.verifiedBy(), row.verifiedAt());
    }

    // ---------- 明细取消 ----------

    @Transactional
    public ProductionTaskViews.TaskView cancelItem(long taskId, long itemId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "取消必须填写原因",
                    List.of(new ApiFieldError("reason", "取消原因必填")));
        }
        repository.findTaskByIdForUpdate(taskId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "生产任务不存在"));
        var item = repository.findItemByIdForUpdate(itemId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "任务明细不存在"));
        if (item.taskId() != taskId) {
            throw new ApiException(ErrorCode.NOT_FOUND, "任务明细不属于该任务");
        }
        if (!STATUS_PENDING.equals(item.status())) {
            throw new ApiException(ErrorCode.STATE_NOT_CANCELABLE);
        }
        var context = reference.item(item.orderItemId());
        if (context != null && context.shippedQuantity() > 0) {
            // 取消后该工序剩余有效覆盖（其他待执行正常计划 + 已核验正常处理）不得低于累计有效发货
            int remainingCoverage = remainingNormalCoverage(item);
            if (context.shippedQuantity() > remainingCoverage) {
                throw new ApiException(ErrorCode.QUANTITY_BELOW_SHIPPED, "取消会使有效安排低于已发货数量",
                        List.of(new ApiFieldError("itemId", "已发货 " + context.shippedQuantity()
                                + "，取消后剩余有效安排 " + remainingCoverage)));
            }
        }
        var audit = auditContext.current();
        if (repository.markItemCancelled(itemId, reason, audit.adminUsername(), audit.requestId()) == 0) {
            throw new ApiException(ErrorCode.STATE_NOT_CANCELABLE);
        }
        releaseOccupation(item, audit.requestId());
        repository.bumpTaskVersion(taskId, audit.requestId());
        return get(taskId);
    }

    /** 取消后释放来源占用：正常明细释放计划占用，回转释放可分配余额，返工/售后恢复来源余额。 */
    private void releaseOccupation(ProductionTaskItemRow item, String requestId) {
        if (SOURCE_REWORK.equals(item.sourceType())) {
            reworkSourceRepository.release(item.sourceId(), item.plannedQuantity(), requestId);
            return;
        }
        if (SOURCE_AFTER_SALES.equals(item.sourceType())) {
            afterSalesSourceRepository.release(item.sourceId(), item.plannedQuantity(), requestId);
            return;
        }
        if (SOURCE_RETURN.equals(item.sourceType())) {
            returnRepository.release(item.sourceId(), item.plannedQuantity(), requestId);
        }
        if (item.isNormalSource()) {
            ledger.applyPlanned(item.orderItemId(), item.node(), item.plannedQuantity(), false, requestId);
        }
    }

    /** 取消该明细后，同订单明细同工序上仍存在的正常有效覆盖（其他待执行正常计划 + 已核验正常处理）。 */
    private int remainingNormalCoverage(ProductionTaskItemRow item) {
        int otherPending = repository.pendingNormalByItemNode(List.of(item.orderItemId()))
                .getOrDefault(item.orderItemId() + ":" + item.node(), 0) - item.plannedQuantity();
        int verified = repository.verifiedByItemNode(List.of(item.orderItemId()))
                .getOrDefault(item.orderItemId() + ":" + item.node(), 0);
        return Math.max(0, otherPending) + verified;
    }

    // ---------- 视图与派生 ----------

    private List<ProductionTaskViews.TaskView> toViews(List<ProductionTaskRow> tasks) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        var taskIds = tasks.stream().map(ProductionTaskRow::id).toList();
        var itemsByTask = new LinkedHashMap<Long, List<ProductionTaskItemRow>>();
        for (var item : repository.findItemsByTasks(taskIds)) {
            itemsByTask.computeIfAbsent(item.taskId(), ignored -> new ArrayList<>()).add(item);
        }
        var orderItemIds = itemsByTask.values().stream().flatMap(List::stream)
                .map(ProductionTaskItemRow::orderItemId).distinct().toList();
        var contexts = reference.items(orderItemIds);
        var verified = repository.verifiedByItemNode(orderItemIds);
        var executable = executableAllocation(itemsByTask, contexts, verified);
        var verificationByItem = new LinkedHashMap<Long, ProductionVerificationRepository.VerificationRow>();
        for (var taskId : taskIds) {
            for (var row : verificationRepository.findByTask(taskId)) {
                verificationByItem.put(row.taskItemId(), row);
            }
        }

        var views = new ArrayList<ProductionTaskViews.TaskView>();
        for (var task : tasks) {
            var itemRows = itemsByTask.getOrDefault(task.id(), List.of());
            var itemViews = itemRows.stream()
                    .map(item -> toItemView(item, contexts.get(item.orderItemId()), verified, executable, task,
                            verificationByItem.get(item.id())))
                    .toList();
            views.add(new ProductionTaskViews.TaskView(task.id(), task.taskNo(), task.taskDate(),
                    task.employeeId(), task.employeeNameSnapshot(), task.workTypeId(),
                    task.workTypeNameSnapshot(), task.taskType(), task.note(), derivedStatus(itemRows, task),
                    itemViews, task.version()));
        }
        return views;
    }

    /**
     * 当前可执行分配：同一订单明细 + 工序下，按任务日期与明细 id 升序把「实际流入 − 已核验处理」先到先得。
     * 计划数量本身不产生可执行量，因此等待上游的明细可执行量为 0。
     */
    private Map<Long, Integer> executableAllocation(Map<Long, List<ProductionTaskItemRow>> itemsByTask,
                                                    Map<Long, OrderProductionReference.OrderItemContext> contexts,
                                                    Map<String, Integer> verified) {
        var pending = new TreeMap<String, List<ProductionTaskItemRow>>();
        for (var items : itemsByTask.values()) {
            for (var item : items) {
                if (!STATUS_PENDING.equals(item.status()) || !item.isNormalSource()) {
                    continue;
                }
                pending.computeIfAbsent(item.orderItemId() + ":" + item.node(), ignored -> new ArrayList<>())
                        .add(item);
            }
        }
        var result = new LinkedHashMap<Long, Integer>();
        for (var entry : pending.entrySet()) {
            var first = entry.getValue().get(0);
            var context = contexts.get(first.orderItemId());
            if (context == null) {
                continue;
            }
            int available = Math.max(0, context.effectiveInflow(first.node())
                    + returnRepository.availableByItemNode(first.orderItemId(), first.node())
                    - verified.getOrDefault(entry.getKey(), 0));
            for (var item : entry.getValue()) {
                int own = Math.min(item.plannedQuantity(), available);
                available -= own;
                result.put(item.id(), own);
            }
        }
        return result;
    }

    private ProductionTaskViews.ItemView toItemView(ProductionTaskItemRow item,
                                                    OrderProductionReference.OrderItemContext context,
                                                    Map<String, Integer> verified, Map<Long, Integer> executable,
                                                    ProductionTaskRow task,
                                                    ProductionVerificationRepository.VerificationRow verification) {
        var time = item.isNormalSource()
                ? ProductionTimeCalculator.calculateNormal(item.plannedQuantity(), item.standardMinutes(),
                        ProductionWorkType.fromNode(item.node()), item.makingEffectiveHourRate(),
                        item.workdayHours())
                : ProductionTimeCalculator.calculateRework(item.plannedQuantity(), item.standardMinutes());
        var usage = item.isNormalSource()
                ? capacityService.usage(item.productId(), task.taskDate(), item.node(), item.moldQuantity(),
                        item.dailyBatchLimit())
                : new ProductionCapacityService.CapacityUsage(0, 0, 0);
        int own = executable.getOrDefault(item.id(), 0);
        int inflow = context == null ? 0 : context.effectiveInflow(item.node());
        return new ProductionTaskViews.ItemView(item.id(), item.itemNo(), item.orderId(),
                context == null ? null : context.orderNo(), item.orderItemId(),
                context == null ? 0 : context.lineNo(), item.productId(), item.productNo(), item.productName(),
                item.node(), item.plannedQuantity(), item.sourceType(), item.sourceId(),
                item.standardMinutes(), item.estimatedMinutes(), time.normalMinutes(), time.reworkMinutes(),
                time.capacityNotice(), usage.dailyMaxCapacity(), usage.usedNormalQuantity(),
                usage.remainingNormalQuantity(), inflow, verified.getOrDefault(
                        item.orderItemId() + ":" + item.node(), 0), own,
                STATUS_PENDING.equals(item.status()) && own == 0, item.status(), item.cancelledBy(),
                item.cancelReason(), toVerificationView(verification), item.version());
    }

    /** 任务头状态完全由明细事实派生，不提供手工状态覆盖。 */
    public static String derivedStatus(List<ProductionTaskItemRow> items, ProductionTaskRow task) {
        if (items.isEmpty()) {
            return "SCHEDULED";
        }
        long pending = items.stream().filter(i -> STATUS_PENDING.equals(i.status())).count();
        long verified = items.stream().filter(i -> "VERIFIED".equals(i.status())).count();
        long cancelled = items.stream().filter(i -> "CANCELLED".equals(i.status())).count();
        if (cancelled == items.size()) {
            return "CANCELLED";
        }
        if (pending == 0) {
            return "VERIFIED";
        }
        if (verified > 0) {
            return "PARTIALLY_VERIFIED";
        }
        return "SCHEDULED";
    }

    // ---------- 校验工具 ----------

    private static String requireTaskType(String taskType, List<ApiFieldError> errors) {
        if (taskType == null || taskType.isBlank()) {
            errors.add(new ApiFieldError("taskType", "任务类型必填"));
            return null;
        }
        var trimmed = taskType.trim();
        if (!TYPE_NORMAL.equals(trimmed) && !TYPE_REWORK.equals(trimmed)) {
            errors.add(new ApiFieldError("taskType", "任务类型只支持 NORMAL 或 REWORK"));
            return null;
        }
        return trimmed;
    }

    /** 工种 id → 系统 code：任务头的工种 code 就是明细工序。 */
    private String workTypeCode(Long workTypeId, List<ApiFieldError> errors) {
        var code = repository.workTypeCode(workTypeId);
        if (code == null) {
            errors.add(new ApiFieldError("workTypeId", "工种不存在"));
            return null;
        }
        if (!ProductionNodes.isNode(code)) {
            errors.add(new ApiFieldError("workTypeId", "生产任务工序必须是制作/捏毛装袋/缝边剪袋"));
            return null;
        }
        return code;
    }

    private String workTypeName(String code) {
        return repository.workTypeName(code);
    }

    static void failIfInvalid(List<ApiFieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID,
                    ErrorCode.VALIDATION_INVALID.defaultMessage(), errors);
        }
    }

    /** 解析完成的明细：落库所需字段 + 订单上下文 + 时间计算结果。 */
    private record PlannedItem(OrderProductionReference.OrderItemContext context, String node, int quantity,
                               String sourceType, long sourceId, int standardMinutes,
                               ProductionTimeResult time,
                               ProductionSnapshotReader.ProductionSnapshot snapshot) {

        ProductionTaskItemRow toRow(long taskId, int itemNo) {
            return new ProductionTaskItemRow(null, taskId, itemNo, context.orderId(), context.orderItemId(),
                    snapshot.productId(), snapshot.productNo(), snapshot.productName(), node, quantity,
                    sourceType, sourceId, standardMinutes, time.normalMinutes(),
                    snapshot.makingEffectiveHourRate(), snapshot.workdayHours(), snapshot.moldQuantity(),
                    snapshot.dailyBatchLimit(), STATUS_PENDING, null, null, 0L);
        }
    }
}

package com.yumi.production.aftersales;

import com.yumi.identity.AuditContext;
import com.yumi.production.ProductionNodes;
import com.yumi.production.aftersales.internal.AfterSalesProductionSourceRepository;
import com.yumi.production.internal.AfterSalesProductionReference;
import com.yumi.production.task.ProductionTaskService;
import com.yumi.production.task.ProductionTaskViews;
import com.yumi.production.task.internal.ProductionTaskRepository;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 售后生产来源应用服务（阶段八 8.4/8.5）：从售后退回返工或补发缺口创建售后生产任务。
 *
 * <p>口径（`docs/architecture/production-module-design.md` §2.2/§11.2、`after-sales-module-design.md` §4.3）：
 * <pre>
 * 售后返工来源额度 = 退回核验的返工数量 − 已安排
 * 补发生产来源额度 = 补发需求 − 已补发 − 可补发 − 已安排
 * </pre>
 *
 * <p><b>来源边界</b>：售后生产使用**自己的来源**（`after_sales_production_sources`，
 * `source_type = AFTER_SALES_SOURCE`），绝不走普通订单需求路径——不登记订单侧计划占用、
 * 不占用产品日产能（`dailyMaxCapacity`）、不产生订单需求或履约事实、不自动进入通用库存；
 * 售后核验、合格流向与补发台账属于阶段八业务，阶段五不写售后业务事实。
 *
 * <p>任务落库统一委托 {@code ProductionTaskService.create}（其来源分支已支持 `AFTER_SALES_SOURCE`：
 * 不占产品日产能、不登记订单侧计划占用）；售后模块只负责来源额度、锁定顺序与安排量，
 * 不复制任务头/明细的编号、快照与状态机口径。
 */
@Service
public class AfterSalesProductionService {

    static final String PURPOSE_REWORK = "REWORK";
    static final String PURPOSE_REPLACEMENT = "REPLACEMENT";
    static final String SOURCE_AFTER_SALES = "AFTER_SALES_SOURCE";
    static final String TASK_TYPE_REWORK = "REWORK";
    static final String TASK_TYPE_NORMAL = "NORMAL";

    private final AfterSalesProductionSourceRepository repository;
    private final AfterSalesProductionReference reference;
    private final ProductionTaskRepository taskRepository;
    private final ProductionTaskService taskService;
    private final AuditContext auditContext;

    public AfterSalesProductionService(AfterSalesProductionSourceRepository repository,
                                       AfterSalesProductionReference reference,
                                       ProductionTaskRepository taskRepository,
                                       ProductionTaskService taskService,
                                       AuditContext auditContext) {
        this.repository = repository;
        this.reference = reference;
        this.taskRepository = taskRepository;
        this.taskService = taskService;
        this.auditContext = auditContext;
    }

    /** 售后单下全部售后生产来源（额度与占用）。 */
    public List<AfterSalesProductionViews.AfterSalesSourceView> listSources(long caseId) {
        return repository.findByCase(caseId).stream()
                .map(row -> new AfterSalesProductionViews.AfterSalesSourceView(row.id(), row.afterSalesItemId(),
                        row.purpose(), row.node(), row.totalQuantity(), row.arrangedQuantity(),
                        row.availableQuantity(), row.reason()))
                .toList();
    }

    @Transactional
    public ProductionTaskViews.TaskView createTask(long caseId,
                                                   AfterSalesProductionViews.CreateAfterSalesTaskRequest request) {
        var errors = new ArrayList<ApiFieldError>();
        String purpose = requirePurpose(request.purpose(), errors);
        String node = requireNode(request.node(), errors);
        int quantity = requirePositiveQuantity(request.quantity(), errors);
        if (request.planDate() == null) {
            errors.add(new ApiFieldError("planDate", "任务日期必填"));
        }
        if (request.employeeId() == null) {
            errors.add(new ApiFieldError("employeeId", "执行员工必填"));
        }
        if (request.afterSalesItemId() == null) {
            errors.add(new ApiFieldError("afterSalesItemId", "售后明细必填"));
        }
        failIfInvalid(errors);

        // 锁定顺序：售后明细行 → 售后来源行；锁定读保证并发创建任务不会各自读到未安排的余额
        var context = reference.itemForUpdate(request.afterSalesItemId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "售后明细不存在"));
        if (context.caseId() != caseId) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "售后明细不属于该售后单",
                    List.of(new ApiFieldError("afterSalesItemId", "售后明细不属于该售后单")));
        }
        int total = PURPOSE_REWORK.equals(purpose) ? context.reworkBalance() : context.replacementBalance();
        if (total <= 0) {
            throw new ApiException(ErrorCode.SOURCE_INSUFFICIENT,
                    PURPOSE_REWORK.equals(purpose) ? "该售后明细没有可返工数量" : "该售后明细没有待生产补发缺口",
                    List.of(new ApiFieldError("quantity", PURPOSE_REWORK.equals(purpose)
                            ? "退回核验的返工数量为 0" : "补发需求已被已补发与可补发覆盖")));
        }
        var existing = repository.findForUpdate(context.afterSalesItemId(), purpose, node).orElse(null);
        int arranged = existing == null ? 0 : existing.arrangedQuantity();
        if (quantity > total - arranged) {
            throw new ApiException(ErrorCode.SOURCE_INSUFFICIENT, "售后来源余额不足",
                    List.of(new ApiFieldError("quantity", "来源可安排 " + (total - arranged)
                            + "，本次 " + quantity)));
        }
        var workTypeId = taskRepository.workTypeId(node);
        if (workTypeId == null) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "来源发生工序不是系统内置工种",
                    List.of(new ApiFieldError("node", "工序不是系统内置工种")));
        }

        // 来源按需创建：唯一键 (after_sales_item_id, purpose, node) 保证同一目标只有一条来源
        var audit = auditContext.current();
        long sourceId;
        if (existing == null) {
            sourceId = repository.insert(context.afterSalesItemId(), context.orderId(), context.orderItemId(),
                    purpose, node, total, request.note(), audit.requestId());
        } else {
            sourceId = existing.id();
            if (total > existing.totalQuantity()) {
                repository.raiseTotal(sourceId, total, audit.requestId());
            }
        }
        String taskType = PURPOSE_REWORK.equals(purpose) ? TASK_TYPE_REWORK : TASK_TYPE_NORMAL;
        var items = List.of(new ProductionTaskService.ItemRequest(context.orderItemId(), quantity,
                SOURCE_AFTER_SALES, sourceId));
        var task = taskService.create(new ProductionTaskService.CreateRequest(request.planDate(),
                request.employeeId(), workTypeId, taskType, request.note(), items));
        repository.arrange(sourceId, quantity, audit.requestId());
        return task;
    }

    private static String requirePurpose(String purpose, List<ApiFieldError> errors) {
        if (purpose == null || !(PURPOSE_REWORK.equals(purpose) || PURPOSE_REPLACEMENT.equals(purpose))) {
            errors.add(new ApiFieldError("purpose", "用途必须是 REWORK 或 REPLACEMENT"));
            return null;
        }
        return purpose;
    }

    private static String requireNode(String node, List<ApiFieldError> errors) {
        if (node == null || !ProductionNodes.isNode(node)) {
            errors.add(new ApiFieldError("node", "工序必须是 MAKING/PACKING_BAG/SEAM_CUTTING"));
            return null;
        }
        return node;
    }

    private static int requirePositiveQuantity(Integer quantity, List<ApiFieldError> errors) {
        if (quantity == null || quantity < 1) {
            errors.add(new ApiFieldError("quantity", "计划数量必须大于 0"));
            return 0;
        }
        return quantity;
    }

    private static void failIfInvalid(List<ApiFieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID,
                    ErrorCode.VALIDATION_INVALID.defaultMessage(), errors);
        }
    }
}

package com.yumi.production.verification;

import com.yumi.identity.AuditContext;
import com.yumi.orders.ledger.AfterSalesLedger;
import com.yumi.orders.ledger.FulfillmentLedger;
import com.yumi.production.aftersales.internal.AfterSalesProductionSourceRepository;
import com.yumi.production.flow.ProductionFlowService;
import com.yumi.production.internal.OrderProductionReference;
import com.yumi.production.reminder.internal.ProductionReminderRepository;
import com.yumi.production.scrap.internal.ProductionQuantityReturnRepository;
import com.yumi.production.source.internal.ReworkSourceRepository;
import com.yumi.production.task.ProductionTaskService;
import com.yumi.production.task.internal.ProductionTaskItemRow;
import com.yumi.production.task.internal.ProductionTaskRepository;
import com.yumi.production.task.internal.ProductionTaskRow;
import com.yumi.production.verification.internal.ProductionVerificationRepository;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 生产核验应用服务（阶段五 5.7/5.8/5.12/5.13）：一个页面一次提交任务内多条明细，后端逐明细校验、整批原子提交。
 *
 * <pre>
 * 本次完成 = 合格 + 返工 + 报废        未完成 = 计划数量 − 本次完成
 * </pre>
 *
 * 合格按冻结流程流入下一节点；返工只形成不可变返工事实与「待安排返工」投影，来源必须由管理员显式创建；
 * 报废写不可变报废事实并生成同工序数量回转，不产生任何替代类型；未完成按来源类型回退。
 * 任一明细失败则整批回滚，不留下部分核验、流转、返工额度、报废事实、回转或提醒。
 */
@Service
public class ProductionVerificationService {

    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_VERIFIED = "VERIFIED";
    static final String STATUS_CANCELLED = "CANCELLED";
    static final String SOURCE_REWORK = "REWORK_SOURCE";
    static final String SOURCE_AFTER_SALES = "AFTER_SALES_SOURCE";
    /** 售后可补发入库事实类型（阶段八口径：售后返工/售后生产合格进入可补发）。 */
    static final String ENTRY_AFTER_SALES_PRODUCTION = "PRODUCTION_INFLOW";
    static final String SOURCE_PRODUCTION = "PRODUCTION";

    private final ProductionTaskRepository taskRepository;
    private final ProductionVerificationRepository verificationRepository;
    private final ProductionQuantityReturnRepository returnRepository;
    private final ReworkSourceRepository reworkSourceRepository;
    private final ProductionReminderRepository reminderRepository;
    private final ProductionFlowService flowService;
    private final OrderProductionReference reference;
    private final FulfillmentLedger ledger;
    private final AfterSalesLedger afterSalesLedger;
    private final AfterSalesProductionSourceRepository afterSalesSourceRepository;
    private final AuditContext auditContext;

    public ProductionVerificationService(ProductionTaskRepository taskRepository,
                                         ProductionVerificationRepository verificationRepository,
                                         ProductionQuantityReturnRepository returnRepository,
                                         ReworkSourceRepository reworkSourceRepository,
                                         ProductionReminderRepository reminderRepository,
                                         ProductionFlowService flowService,
                                         OrderProductionReference reference,
                                         FulfillmentLedger ledger,
                                         AfterSalesLedger afterSalesLedger,
                                         AfterSalesProductionSourceRepository afterSalesSourceRepository,
                                         AuditContext auditContext) {
        this.taskRepository = taskRepository;
        this.verificationRepository = verificationRepository;
        this.returnRepository = returnRepository;
        this.reworkSourceRepository = reworkSourceRepository;
        this.reminderRepository = reminderRepository;
        this.flowService = flowService;
        this.reference = reference;
        this.ledger = ledger;
        this.afterSalesLedger = afterSalesLedger;
        this.afterSalesSourceRepository = afterSalesSourceRepository;
        this.auditContext = auditContext;
    }

    /** 核验入参：只提交明细核验数量与备注，完成/未完成由服务端计算，客户端派生值被忽略。 */
    public record VerifyRequest(List<ItemRequest> items) {
    }

    public record ItemRequest(Long taskItemId, Integer qualifiedQuantity, Integer reworkQuantity,
                              Integer scrapQuantity, String note) {
    }

    @Transactional
    public ProductionVerificationViews.VerificationView verify(long taskId, VerifyRequest request) {
        var audit = auditContext.current();
        // 锁定顺序：任务头 → 明细 id 升序 → 订单履约余额 → 来源/回转（见 production-module-design.md §11.2）
        var task = taskRepository.findTaskByIdForUpdate(taskId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "生产任务不存在"));
        var items = taskRepository.findItemsByTaskForUpdate(taskId);
        var itemsById = new LinkedHashMap<Long, ProductionTaskItemRow>();
        for (var item : items) {
            itemsById.put(item.id(), item);
        }
        var inputs = requireInputs(request, itemsById);
        var orderItemIds = new LinkedHashSet<Long>();
        for (var input : inputs) {
            orderItemIds.add(input.item().orderItemId());
        }
        orderItemIds.stream().sorted().forEach(ledger::lockBalance);
        var contexts = reference.items(orderItemIds);

        var results = new ArrayList<ProductionVerificationViews.ItemResultView>();
        for (var input : inputs) {
            results.add(verifyOne(task, input, contexts.get(input.item().orderItemId()), audit));
        }
        taskRepository.bumpTaskVersion(taskId, audit.requestId());
        return new ProductionVerificationViews.VerificationView(taskId, task.taskNo(),
                ProductionTaskService.derivedStatus(taskRepository.findItemsByTask(taskId), task), results);
    }

    private ProductionVerificationViews.ItemResultView verifyOne(ProductionTaskRow task, Input input,
                                                                OrderProductionReference.OrderItemContext context,
                                                                AuditContext.Context audit) {
        var item = input.item();
        int completed = input.qualified() + input.rework() + input.scrap();
        if (completed > item.plannedQuantity()) {
            throw new ApiException(ErrorCode.VERIFICATION_EQUATION_INVALID, "本次完成不得超过计划数量",
                    List.of(new ApiFieldError(input.prefix() + ".qualifiedQuantity",
                            "计划 " + item.plannedQuantity() + "，本次完成 " + completed)));
        }
        int executable = executableFor(item, context);
        if (completed > executable) {
            throw new ApiException(ErrorCode.QUANTITY_NOT_EXECUTABLE, "本次完成超过当前可执行数量",
                    List.of(new ApiFieldError(input.prefix() + ".qualifiedQuantity",
                            "当前可执行 " + executable + "，本次完成 " + completed)));
        }
        int incomplete = item.plannedQuantity() - completed;
        long verificationId = verificationRepository.insert(item.id(), task.id(), item.orderId(),
                item.orderItemId(), item.productId(), item.node(), item.plannedQuantity(), completed,
                input.qualified(), input.rework(), input.scrap(), incomplete, input.note(),
                audit.adminUsername(), audit.requestId(), audit.idempotencyKey());

        var flows = new ArrayList<ProductionVerificationViews.FlowView>();
        boolean afterSales = SOURCE_AFTER_SALES.equals(item.sourceType());
        if (afterSales) {
            // 售后生产：合格进入售后可补发台账，不写订单工序流入与订单侧计划/已核验投影
            if (input.qualified() > 0) {
                afterSalesLedger.registerInflow(afterSalesItemId(item), ENTRY_AFTER_SALES_PRODUCTION,
                        input.qualified(), SOURCE_PRODUCTION, item.id(), 0, task.taskDate(),
                        audit.adminUsername(), "售后生产合格（" + task.taskNo() + "）", audit.requestId());
                flows.add(new ProductionVerificationViews.FlowView("AFTER_SALES_AVAILABLE", input.qualified()));
            }
        } else if (context != null) {
            for (var flow : flowService.qualifiedFlows(item.node(), input.qualified(), context)) {
                flowService.registerQualified(item, flow, task.taskDate(), task.taskNo(),
                        audit.adminUsername(), audit.requestId());
                flows.add(new ProductionVerificationViews.FlowView(flow.node(), flow.quantity()));
            }
        }
        if (SOURCE_REWORK.equals(item.sourceType())) {
            // 返工明细：不动正常计划占用与已核验处理，只处理返工额度回退
            if (input.rework() > 0) {
                ledger.applyReworkPending(item.orderItemId(), input.rework(), true, audit.requestId());
            }
            if (incomplete > 0) {
                reworkSourceRepository.release(item.sourceId(), incomplete, audit.requestId());
            }
        } else if (afterSales) {
            if (incomplete > 0) {
                // 未完成的售后数量退回来源余额，可重新排产；不产生订单侧待安排与提醒
                afterSalesSourceRepository.release(item.sourceId(), incomplete, audit.requestId());
            }
        } else {
            if (input.rework() > 0) {
                // 返工额度进入「待安排」；来源必须由管理员按返工事实显式创建，核验不隐式建来源
                ledger.applyReworkPending(item.orderItemId(), input.rework(), true, audit.requestId());
            }
            ledger.applyPlanned(item.orderItemId(), item.node(), item.plannedQuantity(), false,
                    audit.requestId());
            ledger.applyVerified(item.orderItemId(), completed, audit.requestId());
        }

        Long reminderId = null;
        if (input.scrap() > 0 && !SOURCE_REWORK.equals(item.sourceType()) && !afterSales) {
            long scrapId = returnRepository.insertScrap(verificationId, item.id(), item.orderId(),
                    item.orderItemId(), item.productId(), item.node(), input.scrap(), input.note(),
                    audit.adminUsername(), audit.requestId(), audit.idempotencyKey());
            returnRepository.insertReturn(scrapId, item.orderId(), item.orderItemId(), item.productId(),
                    item.node(), input.scrap(), audit.requestId(), audit.idempotencyKey());
        }
        if (incomplete > 0 && !SOURCE_REWORK.equals(item.sourceType()) && !afterSales) {
            reminderId = reminderRepository.insertIncomplete(item.orderId(), item.orderItemId(), item.node(),
                    item.id(), verificationId, incomplete, audit.requestId(), audit.idempotencyKey());
        }
        if (taskRepository.markItemVerified(item.id(), audit.requestId()) == 0) {
            throw new ApiException(ErrorCode.STATE_ALREADY_VERIFIED);
        }
        return new ProductionVerificationViews.ItemResultView(item.id(), item.plannedQuantity(), completed,
                input.qualified(), input.rework(), input.scrap(), incomplete, flows, reminderId);
    }

    /** 售后生产来源 → 售后明细 id（合格事实登记到售后可补发台账）。 */
    private long afterSalesItemId(ProductionTaskItemRow item) {
        return afterSalesSourceRepository.findById(item.sourceId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "售后生产来源不存在"))
                .afterSalesItemId();
    }

    /**
     * 当前可执行上限：正常来源只认事实流入；返工来源的可执行上限是**本明细已从来源安排的额度**
     * （来源余额在创建时已校验并占用），来源余额本身是创建期约束而不是核验期约束。
     */
    private int executableFor(ProductionTaskItemRow item, OrderProductionReference.OrderItemContext context) {
        if (SOURCE_REWORK.equals(item.sourceType()) || SOURCE_AFTER_SALES.equals(item.sourceType())) {
            return item.plannedQuantity();
        }
        if (context == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "订单明细不存在");
        }
        return flowService.inflowFor(item, context).executable();
    }

    /** 解析并校验提交的明细：非空、无重复、状态可核验、数量非负。 */
    private List<Input> requireInputs(VerifyRequest request, LinkedHashMap<Long, ProductionTaskItemRow> itemsById) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "至少一条核验明细",
                    List.of(new ApiFieldError("items", "至少一条核验明细")));
        }
        var seen = new LinkedHashSet<Long>();
        var inputs = new ArrayList<Input>();
        for (int index = 0; index < request.items().size(); index++) {
            var item = request.items().get(index);
            var prefix = "items[" + index + "]";
            if (item.taskItemId() == null) {
                throw fieldError(prefix + ".taskItemId", "任务明细必填");
            }
            if (!seen.add(item.taskItemId())) {
                throw fieldError(prefix + ".taskItemId", "同一明细重复提交");
            }
            var row = itemsById.get(item.taskItemId());
            if (row == null) {
                throw fieldError(prefix + ".taskItemId", "任务明细不属于该任务");
            }
            requirePending(row, prefix);
            int qualified = nonNegative(item.qualifiedQuantity(), prefix + ".qualifiedQuantity");
            int rework = nonNegative(item.reworkQuantity(), prefix + ".reworkQuantity");
            int scrap = nonNegative(item.scrapQuantity(), prefix + ".scrapQuantity");
            inputs.add(new Input(row, prefix, qualified, rework, scrap, item.note()));
        }
        return inputs;
    }

    private static void requirePending(ProductionTaskItemRow item, String prefix) {
        if (STATUS_VERIFIED.equals(item.status())) {
            throw new ApiException(ErrorCode.STATE_ALREADY_VERIFIED, "明细已完成核验",
                    List.of(new ApiFieldError(prefix + ".taskItemId", "已核验明细不可重复核验")));
        }
        if (!STATUS_PENDING.equals(item.status())) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "明细状态不可核验",
                    List.of(new ApiFieldError(prefix + ".taskItemId", "当前状态 " + item.status())));
        }
    }

    private static int nonNegative(Integer value, String field) {
        if (value == null || value < 0) {
            throw fieldError(field, "数量必填且不得为负");
        }
        return value;
    }

    private static ApiException fieldError(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_INVALID, ErrorCode.VALIDATION_INVALID.defaultMessage(),
                List.of(new ApiFieldError(field, message)));
    }

    private record Input(ProductionTaskItemRow item, String prefix, int qualified, int rework, int scrap,
                         String note) {
    }
}

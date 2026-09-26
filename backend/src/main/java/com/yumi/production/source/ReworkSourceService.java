package com.yumi.production.source;

import com.yumi.identity.AuditContext;
import com.yumi.orders.ledger.FulfillmentLedger;
import com.yumi.production.task.ProductionTaskService;
import com.yumi.production.task.ProductionTaskViews;
import com.yumi.production.task.internal.ProductionTaskRepository;
import com.yumi.production.verification.internal.ProductionVerificationRepository;
import com.yumi.production.source.internal.ReworkSourceRepository;
import com.yumi.production.source.internal.ReworkSourceRow;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import com.yumi.shared.numbering.SequenceAllocator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 返工来源应用服务（阶段五 5.9–5.11）：核验只记录返工事实，来源必须由管理员显式创建。
 *
 * <p>来源归属「原核验明细 + 发生工序 + 返工轮次」；总量不得超过该返工事实尚未建来源的数量；
 * 可拆成多个 `REWORK` 明细；返工再次返工时基于新的返工事实创建下一轮来源并通过 `previous_source_id` 串联。
 * 阶段五不采用任何基于工序顺序的返工目标限制——返工在发生问题的工序内部完成。
 */
@Service
public class ReworkSourceService {

    private static final String TYPE_REWORK = "REWORK";
    private static final String SOURCE_REWORK = "REWORK_SOURCE";

    private final ReworkSourceRepository repository;
    private final ProductionVerificationRepository verificationRepository;
    private final ProductionTaskRepository taskRepository;
    private final ProductionTaskService taskService;
    private final FulfillmentLedger ledger;
    private final SequenceAllocator sequenceAllocator;
    private final AuditContext auditContext;

    public ReworkSourceService(ReworkSourceRepository repository,
                               ProductionVerificationRepository verificationRepository,
                               ProductionTaskRepository taskRepository,
                               ProductionTaskService taskService,
                               FulfillmentLedger ledger,
                               SequenceAllocator sequenceAllocator,
                               AuditContext auditContext) {
        this.repository = repository;
        this.verificationRepository = verificationRepository;
        this.taskRepository = taskRepository;
        this.taskService = taskService;
        this.ledger = ledger;
        this.sequenceAllocator = sequenceAllocator;
        this.auditContext = auditContext;
    }

    /** 创建返工来源入参：原核验、数量、原因，可选上一轮来源。 */
    public record CreateRequest(Long originVerificationId, Integer quantity, String reason,
                                Long previousSourceId) {
    }

    /** 从来源安排返工任务入参：头共同信息 + 明细数量（产品、订单、工序、轮次由来源解析）。 */
    public record CreateTaskRequest(LocalDate taskDate, Long employeeId, String note, List<TaskItem> items) {
    }

    public record TaskItem(Integer plannedQuantity) {
    }

    @Transactional
    public ReworkSourceViews.ReworkSourceView create(CreateRequest request) {
        if (request.originVerificationId() == null) {
            throw fieldError("originVerificationId", "原核验必填");
        }
        if (request.quantity() == null || request.quantity() < 1) {
            throw fieldError("quantity", "返工来源数量必须大于 0");
        }
        var verificationRow = verificationRepository.findById(request.originVerificationId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "核验事实不存在"));
        if (verificationRow.reworkQuantity() < 1) {
            throw new ApiException(ErrorCode.SOURCE_INVALID, "该核验没有返工事实",
                    List.of(new ApiFieldError("originVerificationId", "核验返工数量为 0")));
        }
        var taskItem = taskRepository.findItemByIdForUpdate(verificationRow.taskItemId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "任务明细不存在"));
        // 锁定同一原核验上已创建的来源（按 id 升序），重算「未建来源额度」
        var existing = repository.findByOriginVerificationForUpdate(verificationRow.id());
        int sourced = existing.stream().mapToInt(ReworkSourceRow::totalQuantity).sum();
        int remaining = verificationRow.reworkQuantity() - sourced;
        if (request.quantity() > remaining) {
            throw new ApiException(ErrorCode.SOURCE_INSUFFICIENT, "返工事实剩余未建来源数量不足",
                    List.of(new ApiFieldError("quantity", "可建来源 " + remaining + "，本次 " + request.quantity())));
        }
        int roundNo = 1;
        if (request.previousSourceId() != null) {
            var parent = repository.findByIdForUpdate(request.previousSourceId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "上一轮返工来源不存在"));
            if (parent.orderItemId() != verificationRow.orderItemId()
                    || !parent.node().equals(verificationRow.node())) {
                throw new ApiException(ErrorCode.SOURCE_INVALID, "下一轮返工来源必须与原来源同订单明细、同工序",
                        List.of(new ApiFieldError("previousSourceId", "来源归属不一致")));
            }
            roundNo = parent.roundNo() + 1;
        }
        var audit = auditContext.current();
        var sourceNo = SequenceAllocator.format("RS", sequenceAllocator.next("rework_sources"), 6);
        var row = new ReworkSourceRow(null, sourceNo, verificationRow.id(), taskItem.id(),
                taskItem.orderId(), taskItem.orderItemId(), taskItem.productId(), taskItem.node(),
                request.quantity(), 0, roundNo, request.previousSourceId(), request.reason(), 0L);
        long id = repository.insert(row, audit.requestId(), audit.idempotencyKey());
        // 来源创建即从「待安排返工」转入来源额度，可重新排产
        ledger.applyReworkPending(taskItem.orderItemId(), request.quantity(), false, audit.requestId());
        return toView(repository.findById(id).orElseThrow());
    }

    public List<ReworkSourceViews.ReworkSourceView> list(Long orderItemId, String node, String status) {
        return repository.find(orderItemId, node, status).stream().map(ReworkSourceService::toView).toList();
    }

    public ReworkSourceViews.ReworkSourceView get(long id) {
        return toView(repository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "返工来源不存在")));
    }

    /** 从来源余额创建一个或多个 REWORK 明细：工序强制等于来源发生工序，不接受普通任务类型冒充。 */
    @Transactional
    public ProductionTaskViews.TaskView createTask(long sourceId, CreateTaskRequest request) {
        var source = repository.findById(sourceId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "返工来源不存在"));
        if (source.availableQuantity() < 1) {
            throw new ApiException(ErrorCode.SOURCE_INSUFFICIENT, "返工来源余额不足",
                    List.of(new ApiFieldError("sourceId", "来源已安排完毕")));
        }
        var workTypeId = taskRepository.workTypeId(source.node());
        if (workTypeId == null) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "来源发生工序不是系统内置工种");
        }
        var items = request.items() == null ? List.<ProductionTaskService.ItemRequest>of()
                : request.items().stream()
                        .map(item -> new ProductionTaskService.ItemRequest(source.orderItemId(),
                                item.plannedQuantity(), SOURCE_REWORK, sourceId))
                        .toList();
        return taskService.create(new ProductionTaskService.CreateRequest(request.taskDate(),
                request.employeeId(), workTypeId, TYPE_REWORK, request.note(), items));
    }

    private static ReworkSourceViews.ReworkSourceView toView(ReworkSourceRow row) {
        return new ReworkSourceViews.ReworkSourceView(row.id(), row.sourceNo(), row.originVerificationId(),
                row.originTaskItemId(), row.orderId(), row.orderItemId(), row.productId(), row.node(),
                row.totalQuantity(), row.arrangedQuantity(), row.availableQuantity(), row.roundNo(),
                row.previousSourceId(), row.reason());
    }

    private static ApiException fieldError(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_INVALID, ErrorCode.VALIDATION_INVALID.defaultMessage(),
                List.of(new ApiFieldError(field, message)));
    }
}

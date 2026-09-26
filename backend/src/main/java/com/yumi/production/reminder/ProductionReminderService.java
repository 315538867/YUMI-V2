package com.yumi.production.reminder;

import com.yumi.identity.AuditContext;
import com.yumi.production.reminder.internal.ProductionReminderRepository;
import com.yumi.production.reminder.internal.ProductionReminderRow;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工作台提醒应用服务（阶段五 5.13）：提醒只是待处理事项，数量事实仍在明细、核验、来源与预占上。
 * 未完成部分回到该工序普通待安排；「暂不安排」只追加处理事实与原因，不改变任何数量。
 */
@Service
public class ProductionReminderService {

    static final String HANDLING_DEFERRED = "DEFERRED";

    private final ProductionReminderRepository repository;
    private final AuditContext auditContext;

    public ProductionReminderService(ProductionReminderRepository repository, AuditContext auditContext) {
        this.repository = repository;
        this.auditContext = auditContext;
    }

    /** 提醒视图：数量、状态、处理结果与原因均可从事实重建。 */
    public record ReminderView(long id, String reminderType, long orderId, long orderItemId, String node,
                               Long taskItemId, Long verificationId, int quantity, String status,
                               String handlingType, Integer handledQuantity, String reason,
                               String handledBy, LocalDateTime handledAt) {
    }

    public List<ReminderView> listIncomplete(Long orderItemId, String node) {
        return repository.findIncomplete(orderItemId, node).stream()
                .map(ProductionReminderService::toView).toList();
    }

    /** 暂不安排：必须填写原因，只追加处理事实，未完成需求保留。 */
    @Transactional
    public ReminderView defer(long id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "暂不安排必须填写原因",
                    List.of(new ApiFieldError("reason", "原因必填")));
        }
        var reminder = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "提醒不存在"));
        if (!ProductionReminderRepository.STATUS_OPEN.equals(reminder.status())) {
            throw new ApiException(ErrorCode.STATE_NOT_EDITABLE, "提醒已处理",
                    List.of(new ApiFieldError("id", "已处理提醒不可重复处理")));
        }
        var audit = auditContext.current();
        if (repository.markHandled(id, HANDLING_DEFERRED, null, reason, audit.adminUsername(),
                audit.requestId()) == 0) {
            throw new ApiException(ErrorCode.STATE_NOT_EDITABLE);
        }
        return repository.findById(id).map(ProductionReminderService::toView).orElseThrow();
    }

    private static ReminderView toView(ProductionReminderRow row) {
        return new ReminderView(row.id(), row.reminderType(), row.orderId(), row.orderItemId(), row.node(),
                row.taskItemId(), row.verificationId(), row.quantity(), row.status(), row.handlingType(),
                row.handledQuantity(), row.reason(), row.handledBy(), row.handledAt());
    }
}

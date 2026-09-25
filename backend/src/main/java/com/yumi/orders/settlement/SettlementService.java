package com.yumi.orders.settlement;

import com.yumi.calculation.DecimalPolicy;
import com.yumi.identity.AuditContext;
import com.yumi.orders.aftersales.internal.AfterSalesRepository;
import com.yumi.orders.change.internal.OrderChangeRepository;
import com.yumi.orders.order.internal.OrderRepository;
import com.yumi.orders.order.internal.OrderRow;
import com.yumi.orders.order.internal.OrderSnapshotRepository;
import com.yumi.orders.settlement.internal.SettlementRepository;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import com.yumi.shared.numbering.SequenceAllocator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 收退款与关闭应用服务（任务 7.2–7.6）。
 *
 * 口径（`specs/order-lifecycle/spec.md`、`design.md` §7.1、施工文档 §4/§5）：
 * <pre>
 * 订单结清净额 = 累计订单收款 − 累计订单变更退款
 * 订单待退款   = max(累计订单收款 − 当前有效应收 − 累计订单变更退款, 0)
 * 累计实际净收 = 订单结清净额 − 累计售后退款
 * </pre>
 * 收款与退款只追加、不修改删除；退款必须关联来源；累计退款（含售后）不得超过累计收款。
 * 关闭必须同时满足「有效需求全部有效发货」「应收结清」「无待退款」，生产完成或成品余量不能替代交付。
 */
@Service
public class SettlementService {

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String SOURCE_ORDER_CHANGE = "ORDER_CHANGE";
    private static final String SOURCE_AFTER_SALES = "AFTER_SALES";

    private final SettlementRepository repository;
    private final OrderRepository orderRepository;
    private final OrderSnapshotRepository snapshotRepository;
    private final OrderChangeRepository changeRepository;
    private final AfterSalesRepository afterSalesRepository;
    private final SequenceAllocator sequenceAllocator;
    private final AuditContext auditContext;

    public SettlementService(SettlementRepository repository, OrderRepository orderRepository,
                             OrderSnapshotRepository snapshotRepository, OrderChangeRepository changeRepository,
                             AfterSalesRepository afterSalesRepository, SequenceAllocator sequenceAllocator,
                             AuditContext auditContext) {
        this.repository = repository;
        this.orderRepository = orderRepository;
        this.snapshotRepository = snapshotRepository;
        this.changeRepository = changeRepository;
        this.afterSalesRepository = afterSalesRepository;
        this.sequenceAllocator = sequenceAllocator;
        this.auditContext = auditContext;
    }

    // ---------- 查询（任务 7.4/7.7 的数据面） ----------

    public SettlementViews.SettlementView view(long orderId) {
        var order = requireOrder(orderId);
        var totals = totalsOf(order);
        return toView(order, totals);
    }

    // ---------- 收款（任务 7.2） ----------

    @Transactional
    public SettlementViews.SettlementView registerPayment(long orderId, SettlementViews.PaymentRequest request) {
        var order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        if (STATUS_DRAFT.equals(order.status())) {
            throw new ApiException(ErrorCode.PAYMENT_DRAFT_FORBIDDEN);
        }
        if (!STATUS_CONFIRMED.equals(order.status())) {
            throw new ApiException(ErrorCode.STATE_NOT_EDITABLE, "关闭或取消后不得新增原订单收款",
                    List.of(new ApiFieldError("orderId", "当前订单状态 " + order.status())));
        }
        var errors = new ArrayList<ApiFieldError>();
        var amount = requirePositiveAmount(request.amount(), errors);
        requireDate(request.businessDate(), "businessDate", errors);
        if (request.method() == null || request.method().isBlank()) {
            errors.add(new ApiFieldError("method", "收款方式必填"));
        }
        failIfInvalid(errors);

        var audit = auditContext.current();
        var paymentNo = SequenceAllocator.format("PA", sequenceAllocator.next("payments"), 6);
        repository.insertPayment(paymentNo, orderId, amount, request.businessDate(), request.method(),
                request.note(), audit.adminUsername(), audit.requestId());
        refreshProjection(order, audit.requestId());
        return view(orderId);
    }

    // ---------- 退款（任务 7.3） ----------

    @Transactional
    public SettlementViews.SettlementView registerRefund(long orderId, SettlementViews.RefundRequest request) {
        var order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        if (STATUS_DRAFT.equals(order.status())) {
            throw new ApiException(ErrorCode.PAYMENT_DRAFT_FORBIDDEN);
        }
        var errors = new ArrayList<ApiFieldError>();
        var amount = requirePositiveAmount(request.amount(), errors);
        requireDate(request.businessDate(), "businessDate", errors);
        if (request.method() == null || request.method().isBlank()) {
            errors.add(new ApiFieldError("method", "退款方式必填"));
        }
        if (request.reason() == null || request.reason().isBlank()) {
            errors.add(new ApiFieldError("reason", "退款原因必填"));
        }
        if (request.sourceType() == null
                || !(SOURCE_ORDER_CHANGE.equals(request.sourceType()) || SOURCE_AFTER_SALES.equals(request.sourceType()))) {
            errors.add(new ApiFieldError("sourceType", "退款来源必须是 ORDER_CHANGE 或 AFTER_SALES"));
        }
        if (request.sourceId() == null) {
            errors.add(new ApiFieldError("sourceId", "退款来源记录必填"));
        }
        failIfInvalid(errors);

        // 退款必须关联来源：订单变更退款要关联本单的变更单
        if (SOURCE_ORDER_CHANGE.equals(request.sourceType())) {
            var change = changeRepository.findById(request.sourceId()).orElse(null);
            if (change == null || change.orderId() != orderId) {
                throw new ApiException(ErrorCode.REFUND_REFERENCE_REQUIRED, "订单变更退款必须关联本单的变更单",
                        List.of(new ApiFieldError("sourceId", "变更单不存在或不属于该订单")));
            }
        }
        // 售后退款：必须关联本单的售后单（阶段八接入后生效）
        if (SOURCE_AFTER_SALES.equals(request.sourceType())) {
            var salesCase = afterSalesRepository.findCase(request.sourceId()).orElse(null);
            if (salesCase == null || salesCase.orderId() != orderId) {
                throw new ApiException(ErrorCode.REFUND_REFERENCE_REQUIRED, "售后退款必须关联本单的售后单",
                        List.of(new ApiFieldError("sourceId", "售后单不存在或不属于该订单")));
            }
        }
        var paid = repository.sumPayments(orderId);
        var refunded = repository.sumRefunds(orderId, null);
        if (refunded.add(amount).compareTo(paid) > 0) {
            throw new ApiException(ErrorCode.REFUND_EXCEEDS_RECEIPTS, "退款累计不得超过累计收款",
                    List.of(new ApiFieldError("amount", "累计收款 " + paid + "，已退 " + refunded
                            + "，本次 " + amount)));
        }

        var audit = auditContext.current();
        var refundNo = SequenceAllocator.format("RF", sequenceAllocator.next("refunds"), 6);
        repository.insertRefund(refundNo, orderId, amount, request.businessDate(), request.method(),
                request.reason(), request.note(), request.sourceType(), request.sourceId(),
                audit.adminUsername(), audit.requestId());
        refreshProjection(order, audit.requestId());
        return view(orderId);
    }

    // ---------- 关闭（任务 7.5/7.6） ----------

    @Transactional
    public SettlementViews.SettlementView close(long orderId) {
        var order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        if (STATUS_CLOSED.equals(order.status())) {
            throw new ApiException(ErrorCode.STATE_NOT_CANCELABLE, "订单已关闭，不得重开",
                    List.of(new ApiFieldError("orderId", "已关闭是终态")));
        }
        if (STATUS_CANCELLED.equals(order.status())) {
            throw new ApiException(ErrorCode.STATE_NOT_CANCELABLE, "订单已取消，不能关闭",
                    List.of(new ApiFieldError("orderId", "已取消是终态")));
        }
        if (!STATUS_CONFIRMED.equals(order.status())) {
            throw new ApiException(ErrorCode.STATE_NOT_CANCELABLE, "只有已确认订单可以关闭",
                    List.of(new ApiFieldError("orderId", "当前状态 " + order.status())));
        }
        var audit = auditContext.current();
        var totals = totalsOf(order);
        // 事务内重验：履约 → 应收结清 → 无待退款
        var fulfillment = fulfillmentPending(orderId);
        if (fulfillment != null) {
            throw new ApiException(ErrorCode.CLOSE_FULFILLMENT_PENDING, "仍有未发货的有效需求",
                    List.of(new ApiFieldError("fulfillment", fulfillment)));
        }
        if (totals.netSettled().compareTo(totals.effectiveReceivable()) < 0) {
            throw new ApiException(ErrorCode.CLOSE_SETTLEMENT_PENDING, "当前有效应收尚未结清",
                    List.of(new ApiFieldError("settlement", "结清净额 " + totals.netSettled()
                            + " < 当前有效应收 " + totals.effectiveReceivable())));
        }
        if (totals.refundPending().compareTo(BigDecimal.ZERO) > 0) {
            throw new ApiException(ErrorCode.CLOSE_REFUND_PENDING, "存在待退款，请先处理退款",
                    List.of(new ApiFieldError("refund", "待退款 " + totals.refundPending())));
        }
        if (snapshotRepository.markClosed(orderId, audit.adminUsername(), audit.requestId()) == 0) {
            throw new ApiException(ErrorCode.STATE_NOT_CANCELABLE, "订单状态已变化，请刷新后重试",
                    List.of(new ApiFieldError("orderId", "并发关闭或状态已变更")));
        }
        refreshProjection(order, audit.requestId());
        return view(orderId);
    }

    // ---------- 内部 ----------

    private record Totals(BigDecimal paid, BigDecimal changeRefund, BigDecimal afterSalesRefund,
                          BigDecimal netSettled, BigDecimal actualNetReceived, BigDecimal effectiveReceivable,
                          BigDecimal refundPending) {
    }

    /** 结清口径统一实现：全部由不可变事实与订单当前有效应收派生。 */
    private Totals totalsOf(OrderRow order) {
        var paid = repository.sumPayments(order.id());
        var changeRefund = repository.sumRefunds(order.id(), SOURCE_ORDER_CHANGE);
        var afterSalesRefund = repository.sumRefunds(order.id(), SOURCE_AFTER_SALES);
        var netSettled = DecimalPolicy.money(paid.subtract(changeRefund));
        var actualNetReceived = DecimalPolicy.money(netSettled.subtract(afterSalesRefund));
        var effectiveReceivable = order.receivableAmount();
        var pending = paid.subtract(effectiveReceivable).subtract(changeRefund);
        var refundPending = pending.compareTo(BigDecimal.ZERO) > 0 ? DecimalPolicy.money(pending)
                : DecimalPolicy.money(BigDecimal.ZERO);
        return new Totals(DecimalPolicy.money(paid), DecimalPolicy.money(changeRefund),
                DecimalPolicy.money(afterSalesRefund), netSettled, actualNetReceived, effectiveReceivable,
                refundPending);
    }

    private void refreshProjection(OrderRow order, String requestId) {
        var totals = totalsOf(order);
        repository.updateSettlement(new SettlementRepository.SettlementRow(order.id(), totals.paid(),
                totals.changeRefund(), totals.afterSalesRefund(), totals.netSettled(),
                totals.effectiveReceivable(), totals.refundPending()), requestId);
    }

    /**
     * 返回第一条未交付明细的说明；全部有效发货返回 null。
     * 生产完成与成品余量不参与本判定——它们不能替代交付（施工文档 §5.3）。
     */
    private String fulfillmentPending(long orderId) {
        for (var row : repository.findDeliveryGaps(orderId)) {
            if (row[1] < row[0]) {
                return "明细 #" + row[2] + " 需求 " + row[0] + "，已发 " + row[1];
            }
        }
        return null;
    }

    private SettlementViews.SettlementView toView(OrderRow order, Totals totals) {
        var payments = repository.findPayments(order.id()).stream()
                .map(row -> new SettlementViews.PaymentView(row.id(), row.paymentNo(), row.amount(),
                        row.businessDate(), row.method(), row.note(), row.operatorUsername()))
                .toList();
        var refunds = repository.findRefunds(order.id()).stream()
                .map(row -> new SettlementViews.RefundView(row.id(), row.refundNo(), row.amount(),
                        row.businessDate(), row.method(), row.reason(), row.note(), row.sourceType(),
                        row.sourceId(), row.operatorUsername()))
                .toList();
        var fulfillmentDetail = fulfillmentPending(order.id());
        var conditions = List.of(
                new SettlementViews.CloseConditionView("有效需求全部有效发货",
                        fulfillmentDetail == null, fulfillmentDetail == null ? "已全部发货" : fulfillmentDetail),
                new SettlementViews.CloseConditionView("当前有效应收已结清",
                        totals.netSettled().compareTo(totals.effectiveReceivable()) >= 0,
                        "结清净额 " + totals.netSettled() + " / 当前有效应收 " + totals.effectiveReceivable()),
                new SettlementViews.CloseConditionView("无待退款",
                        totals.refundPending().compareTo(BigDecimal.ZERO) == 0,
                        "待退款 " + totals.refundPending()));
        return new SettlementViews.SettlementView(order.id(), order.orderNo(), order.status(),
                receivableStatus(order.status(), totals), totals.paid(), totals.changeRefund(),
                totals.afterSalesRefund(), totals.netSettled(), totals.actualNetReceived(),
                totals.effectiveReceivable(), totals.refundPending(), payments, refunds, conditions);
    }

    private static String receivableStatus(String orderStatus, Totals totals) {
        if (STATUS_CANCELLED.equals(orderStatus)) {
            return "已取消";
        }
        if (STATUS_CLOSED.equals(orderStatus)) {
            return "已关闭";
        }
        if (totals.netSettled().compareTo(BigDecimal.ZERO) == 0) {
            return "未收款";
        }
        if (totals.refundPending().compareTo(BigDecimal.ZERO) > 0) {
            return "待退款";
        }
        return totals.netSettled().compareTo(totals.effectiveReceivable()) >= 0 ? "已结清" : "部分收款";
    }

    private OrderRow requireOrder(long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
    }

    private static BigDecimal requirePositiveAmount(BigDecimal amount, List<ApiFieldError> errors) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            errors.add(new ApiFieldError("amount", "金额必须大于 0"));
            return BigDecimal.ZERO;
        }
        return DecimalPolicy.money(amount);
    }

    private static void requireDate(LocalDate date, String field, List<ApiFieldError> errors) {
        if (date == null) {
            errors.add(new ApiFieldError(field, "业务日期必填"));
        }
    }

    private static void failIfInvalid(List<ApiFieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID,
                    ErrorCode.VALIDATION_INVALID.defaultMessage(), errors);
        }
    }
}

package com.yumi.orders.aftersales;

import com.yumi.identity.AuditContext;
import com.yumi.orders.aftersales.internal.AfterSalesRepository;
import com.yumi.orders.order.internal.OrderRepository;
import com.yumi.orders.shipment.internal.ShipmentRepository;
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
 * 售后应用服务（任务 8.2–8.9）。
 *
 * 口径（`specs/order-lifecycle/spec.md`、`domain-and-quantity-model.md` §12、施工文档 §4）：
 * 受理来源必须是**已确认且有效的原发货批次明细**，受理量不超过剩余可受理量（同一发货明细只允许一个有效占用）；
 * 退回 = 返工 + 报废，退回不自动入库、不恢复原发货库存；补发来源只增加可补发，确认补发才增加已补发；
 * **售后不回写原订单**的订购数量、累计发货、未交付需求、应收与主状态。
 */
@Service
public class AfterSalesService {

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String SHIPMENT_CONFIRMED = "CONFIRMED";
    private static final String TARGET_RETURN_VERIFICATION = "RETURN_VERIFICATION";
    private static final String ENTRY_CONSUME = "REPLACEMENT_CONSUME";

    private final AfterSalesRepository repository;
    private final OrderRepository orderRepository;
    private final ShipmentRepository shipmentRepository;
    private final SequenceAllocator sequenceAllocator;
    private final AuditContext auditContext;

    public AfterSalesService(AfterSalesRepository repository, OrderRepository orderRepository,
                             ShipmentRepository shipmentRepository, SequenceAllocator sequenceAllocator,
                             AuditContext auditContext) {
        this.repository = repository;
        this.orderRepository = orderRepository;
        this.shipmentRepository = shipmentRepository;
        this.sequenceAllocator = sequenceAllocator;
        this.auditContext = auditContext;
    }

    public List<AfterSalesViews.AfterSalesCaseView> list(long orderId) {
        return repository.findCasesByOrder(orderId).stream().map(this::toView).toList();
    }

    public AfterSalesViews.AfterSalesCaseView get(long caseId) {
        return toView(requireCase(caseId));
    }

    // ---------- 创建售后（任务 8.2） ----------

    @Transactional
    public AfterSalesViews.AfterSalesCaseView create(long orderId,
                                                     AfterSalesViews.CreateAfterSalesRequest request) {
        var order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        if (STATUS_DRAFT.equals(order.status())) {
            throw new ApiException(ErrorCode.STATE_NOT_EDITABLE, "草稿订单不能创建售后",
                    List.of(new ApiFieldError("orderId", "订单仍为草稿")));
        }
        var errors = new ArrayList<ApiFieldError>();
        if (request.problem() == null || request.problem().isBlank()) {
            errors.add(new ApiFieldError("problem", "问题描述必填"));
        }
        if (request.items() == null || request.items().isEmpty()) {
            errors.add(new ApiFieldError("items", "至少一条售后明细"));
        }
        failIfInvalid(errors);

        var audit = auditContext.current();
        var caseNo = SequenceAllocator.format("AS", sequenceAllocator.next("after_sales_cases"), 6);
        long caseId = repository.insertCase(caseNo, orderId, requireCaseType(request.caseType()),
                request.problem(), request.solution(), request.note(), audit.requestId());
        for (int index = 0; index < request.items().size(); index++) {
            var line = request.items().get(index);
            var prefix = "items[" + index + "]";
            if (line.shipmentItemId() == null) {
                throw new ApiException(ErrorCode.AFTER_SALES_SOURCE_INVALID, "必须引用原发货批次明细",
                        List.of(new ApiFieldError(prefix + ".shipmentItemId", "发货批次明细必填")));
            }
            if (line.acceptedQuantity() == null || line.acceptedQuantity() < 1) {
                throw new ApiException(ErrorCode.VALIDATION_INVALID, "受理数量必须大于 0",
                        List.of(new ApiFieldError(prefix + ".acceptedQuantity", "受理数量必须大于 0")));
            }
            // 锁定读原发货明细：草稿/已作废批次、不属于该订单、超量都拒绝
            var source = repository.lockSource(line.shipmentItemId()).orElse(null);
            if (source == null || !SHIPMENT_CONFIRMED.equals(source.shipmentStatus())) {
                throw new ApiException(ErrorCode.AFTER_SALES_SOURCE_INVALID, "售后来源必须是已确认且有效的发货批次明细",
                        List.of(new ApiFieldError(prefix + ".shipmentItemId",
                                source == null ? "发货批次明细不存在" : "批次状态 " + source.shipmentStatus())));
            }
            if (source.afterSalesReplacement()) {
                // 售后补发批次是补发品，不是「原发货批次明细」，不能再次作为售后来源（否则同一物理流程会自引用）
                throw new ApiException(ErrorCode.AFTER_SALES_SOURCE_INVALID, "售后来源不能是售后补发批次",
                        List.of(new ApiFieldError(prefix + ".shipmentItemId",
                                "批次 " + source.shipmentNo() + " 是售后补发批次，只能以原始发货批次作为售后来源")));
            }
            var orderItem = orderRepository.findItems(orderId).stream()
                    .filter(row -> row.id() == source.orderItemId()).findFirst().orElse(null);
            if (orderItem == null) {
                throw new ApiException(ErrorCode.AFTER_SALES_SOURCE_INVALID, "售后来源明细不属于该订单",
                        List.of(new ApiFieldError(prefix + ".shipmentItemId", "明细不属于该订单")));
            }
            if (repository.findItemByShipmentItem(source.shipmentItemId()).isPresent()) {
                throw new ApiException(ErrorCode.AFTER_SALES_QUANTITY_EXCEEDED, "该发货明细已有有效售后占用",
                        List.of(new ApiFieldError(prefix + ".shipmentItemId",
                                "同一发货批次明细只允许一个有效售后占用")));
            }
            if (line.acceptedQuantity() > source.quantity()) {
                throw new ApiException(ErrorCode.AFTER_SALES_QUANTITY_EXCEEDED, "受理数量超过该发货明细的有效已发数量",
                        List.of(new ApiFieldError(prefix + ".acceptedQuantity",
                                "该发货明细已发 " + source.quantity() + "，本次受理 " + line.acceptedQuantity())));
            }
            repository.insertItem(caseId, orderId, source.orderItemId(), source.shipmentItemId(),
                    source.productNo(), source.productName(), orderItem.seamQuantity(),
                    line.acceptedQuantity(), orZero(line.returnedQuantity()),
                    orZero(line.replacementRequiredQuantity()), audit.requestId());
        }
        return get(caseId);
    }

    // ---------- 退回核验（任务 8.3） ----------

    @Transactional
    public AfterSalesViews.AfterSalesCaseView verifyReturn(long caseId,
                                                          AfterSalesViews.VerifyReturnRequest request) {
        var salesCase = requireCase(caseId);
        var item = request.afterSalesItemId() == null ? null
                : repository.findItemForUpdate(request.afterSalesItemId()).orElse(null);
        if (item == null || item.caseId() != caseId) {
            throw new ApiException(ErrorCode.NOT_FOUND, "售后明细不存在",
                    List.of(new ApiFieldError("afterSalesItemId", "售后明细不存在或不属于该售后单")));
        }
        if (item.returnVerificationId() != null) {
            throw new ApiException(ErrorCode.STATE_ALREADY_VERIFIED, "该售后明细已核验",
                    List.of(new ApiFieldError("afterSalesItemId", "退回核验只能一次")));
        }
        int returned = orZero(request.returnedQuantity());
        int rework = orZero(request.reworkQuantity());
        int scrap = orZero(request.scrapQuantity());
        if (returned != rework + scrap) {
            throw new ApiException(ErrorCode.AFTER_SALES_EQUATION_INVALID, "退回数量必须等于售后返工 + 售后报废",
                    List.of(new ApiFieldError("returnedQuantity",
                            "退回 " + returned + " ≠ 返工 " + rework + " + 报废 " + scrap)));
        }
        if (returned > item.acceptedQuantity()) {
            throw new ApiException(ErrorCode.AFTER_SALES_QUANTITY_EXCEEDED, "退回数量超过受理数量",
                    List.of(new ApiFieldError("returnedQuantity",
                            "受理 " + item.acceptedQuantity() + "，退回 " + returned)));
        }
        var audit = auditContext.current();
        long verificationId = repository.insertReturnVerification(item.id(), returned, rework, scrap,
                request.reason(), audit.adminUsername(), audit.requestId());
        repository.markReturnVerified(item.id(), verificationId, returned, audit.requestId());
        return get(salesCase.id());
    }

    // ---------- 补发发货（任务 8.7） ----------

    @Transactional
    public AfterSalesViews.AfterSalesCaseView createReplacementShipment(
            long caseId, AfterSalesViews.ReplacementShipmentRequest request) {
        var salesCase = requireCase(caseId);
        if (request.items() == null || request.items().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "至少一条补发明细",
                    List.of(new ApiFieldError("items", "至少一条补发明细")));
        }
        var order = orderRepository.findById(salesCase.orderId())
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        var audit = auditContext.current();
        var shipmentNo = SequenceAllocator.format("SH", sequenceAllocator.next("shipments"), 6);
        long shipmentId = shipmentRepository.insert(shipmentNo, order.id(), LocalDate.now(), null, null,
                BigDecimal.ZERO, null, "售后补发", audit.requestId());
        for (int index = 0; index < request.items().size(); index++) {
            var line = request.items().get(index);
            var prefix = "items[" + index + "]";
            var item = line.afterSalesItemId() == null ? null
                    : repository.findItemForUpdate(line.afterSalesItemId()).orElse(null);
            if (item == null || item.caseId() != caseId) {
                throw new ApiException(ErrorCode.NOT_FOUND, "售后明细不存在",
                        List.of(new ApiFieldError(prefix + ".afterSalesItemId", "售后明细不存在或不属于该售后单")));
            }
            int quantity = orZero(line.quantity());
            // 草稿补发批次不占用可补发（与发货草稿同理）；余额校验在确认时进行
            if (quantity < 1) {
                throw new ApiException(ErrorCode.VALIDATION_INVALID, "补发数量必须大于 0",
                        List.of(new ApiFieldError(prefix + ".quantity", "补发数量必须大于 0")));
            }
            var orderItem = orderRepository.findItems(order.id()).stream()
                    .filter(row -> row.id() == item.orderItemId()).findFirst().orElseThrow();
            long shipmentItemId = shipmentRepository.insertItem(shipmentId, item.orderItemId(),
                    orderItem.lineNo(), quantity, item.productNo(), item.productName(), order.recipientName(),
                    order.recipientPhone(), order.region(), order.address(), audit.requestId());
            repository.insertShipmentLink(item.id(), shipmentId, shipmentItemId, quantity, audit.requestId());
        }
        return get(caseId);
    }

    /** 补发确认：消耗售后可补发、增加已补发；原订单统计不变。 */
    @Transactional
    public AfterSalesViews.AfterSalesCaseView confirmReplacementShipment(long caseId, long shipmentId) {
        var salesCase = requireCase(caseId);
        var shipment = shipmentRepository.findByIdForUpdate(shipmentId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "补发批次不存在"));
        if (!"DRAFT".equals(shipment.status())) {
            throw new ApiException(ErrorCode.STATE_NOT_CONFIRMABLE, "只有草稿补发批次可以确认",
                    List.of(new ApiFieldError("shipmentId", "当前状态 " + shipment.status())));
        }
        var audit = auditContext.current();
        for (var shipmentItem : shipmentRepository.findItems(shipmentId)) {
            var item = repository.findItemForUpdate(findAfterSalesItemId(caseId, shipmentItem.id()))
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "售后明细不存在"));
            int available = repository.lockAvailableQuantity(item.id());
            int shipped = repository.lockShippedQuantity(item.id());
            requireReplacementWithinBalance(item, shipmentItem.quantity(), available, shipped, "items");
            repository.insertEntry(item.id(), ENTRY_CONSUME, "OUT", shipmentItem.quantity(),
                    "AFTER_SALES_REPLACEMENT", shipmentId, shipmentItem.id(), LocalDate.now(),
                    audit.adminUsername(), "售后补发发货（" + shipment.shipmentNo() + "）", audit.requestId());
            shipmentRepository.markItemConfirmed(shipmentItem.id(), shipmentItem.quantity() + shipped,
                    Math.max(0, item.replacementRequiredQuantity() - shipped - shipmentItem.quantity()),
                    audit.requestId());
        }
        shipmentRepository.markConfirmed(shipmentId, audit.adminUsername(), audit.requestId());
        return get(salesCase.id());
    }

    // ---------- 更正（任务 8.9） ----------

    @Transactional
    public AfterSalesViews.AfterSalesCaseView correct(long caseId, AfterSalesViews.CorrectionRequest request) {
        var salesCase = requireCase(caseId);
        if (request.reason() == null || request.reason().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "更正原因必填",
                    List.of(new ApiFieldError("reason", "请填写更正原因")));
        }
        if (!TARGET_RETURN_VERIFICATION.equals(request.targetType())) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "首期只支持更正退回核验",
                    List.of(new ApiFieldError("targetType", "仅支持 RETURN_VERIFICATION")));
        }
        var item = request.targetId() == null ? null : repository.findItem(request.targetId()).orElse(null);
        if (item == null || item.caseId() != caseId || item.returnVerificationId() == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "退回核验不存在",
                    List.of(new ApiFieldError("targetId", "退回核验不存在或不属于该售后单")));
        }
        var verification = repository.findReturnVerification(item.id()).orElseThrow();
        var audit = auditContext.current();
        repository.insertCorrection(caseId, TARGET_RETURN_VERIFICATION, verification.id(),
                String.valueOf(verification.returnedQuantity()),
                request.afterValue() == null ? "" : request.afterValue(), request.reason(),
                audit.adminUsername(), audit.requestId());
        return get(salesCase.id());
    }

    // ---------- 内部 ----------

    private void requireReplacementWithinBalance(AfterSalesRepository.ItemRow item, int quantity, int available,
                                                 int shipped, String prefix) {
        if (quantity < 1) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "补发数量必须大于 0",
                    List.of(new ApiFieldError(prefix + ".quantity", "补发数量必须大于 0")));
        }
        if (quantity > available || shipped + quantity > item.replacementRequiredQuantity()) {
            throw new ApiException(ErrorCode.AFTER_SALES_REPLACEMENT_INSUFFICIENT, "售后可补发数量不足",
                    List.of(new ApiFieldError(prefix + ".quantity", "可补发 " + available + "，已补发 " + shipped
                            + "，补发需求 " + item.replacementRequiredQuantity() + "，本次 " + quantity)));
        }
    }

    private long findAfterSalesItemId(long caseId, long replacementShipmentItemId) {
        return repository.findLinkedAfterSalesItemId(caseId, replacementShipmentItemId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "该补发批次明细没有关联售后明细",
                        List.of(new ApiFieldError("shipmentId", "补发明细未关联售后明细"))));
    }

    private AfterSalesRepository.CaseRow requireCase(long caseId) {
        return repository.findCase(caseId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "售后单不存在"));
    }

    private AfterSalesViews.AfterSalesCaseView toView(AfterSalesRepository.CaseRow row) {
        var items = repository.findItems(row.id()).stream().map(item -> {
            var verification = repository.findReturnVerification(item.id()).orElse(null);
            int available = repository.availableQuantity(item.id());
            int shipped = repository.shippedQuantity(item.id());
            return new AfterSalesViews.AfterSalesItemView(item.id(), item.orderItemId(), item.shipmentItemId(),
                    repository.findSource(item.shipmentItemId()).map(AfterSalesRepository.SourceRow::shipmentNo)
                            .orElse(null),
                    item.productNo(), item.productName(), item.seamQuantity(), item.acceptedQuantity(),
                    item.returnedQuantity(), item.replacementRequiredQuantity(),
                    verification == null ? null : verification.reworkQuantity(),
                    verification == null ? null : verification.scrapQuantity(),
                    item.returnVerificationId() != null, available, shipped,
                    Math.max(0, item.replacementRequiredQuantity() - shipped));
        }).toList();
        var corrections = repository.findCorrections(row.id()).stream()
                .map(entry -> new AfterSalesViews.CorrectionView(entry.id(), entry.targetId(),
                        entry.beforeValue(), entry.afterValue(), entry.reason()))
                .toList();
        return new AfterSalesViews.AfterSalesCaseView(row.id(), row.caseNo(), row.orderId(), row.caseType(),
                row.status(), row.problem(), row.solution(), row.note(), items, repository.findRefunds(row.id()),
                corrections, repository.findReplacementShipmentIds(row.id()));
    }

    private static String requireCaseType(String caseType) {
        if (caseType == null || !(caseType.equals("REWORK") || caseType.equals("REPLACEMENT")
                || caseType.equals("REWORK_AND_REPLACEMENT"))) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "售后类型不合法",
                    List.of(new ApiFieldError("caseType", "仅支持 REWORK/REPLACEMENT/REWORK_AND_REPLACEMENT")));
        }
        return caseType;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static void failIfInvalid(List<ApiFieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID,
                    ErrorCode.VALIDATION_INVALID.defaultMessage(), errors);
        }
    }
}

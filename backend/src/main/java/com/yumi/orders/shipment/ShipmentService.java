package com.yumi.orders.shipment;

import com.yumi.identity.AuditContext;
import com.yumi.orders.fulfillment.internal.FulfillmentRepository;
import com.yumi.orders.ledger.FulfillmentLedger;
import com.yumi.orders.order.internal.OrderItemRow;
import com.yumi.orders.order.internal.OrderRepository;
import com.yumi.orders.order.internal.OrderRow;
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
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 发货应用服务（任务 6.2–6.7）。
 *
 * 口径（`domain-and-quantity-model.md` §9，施工文档 §4）：
 * 草稿**不占用**可发货、不增加累计发货、不写事实；确认在同一事务内按明细 id 升序锁履约投影行，
 * 校验「本次 ≤ 当前可发货」与「累计有效发货 + 本次 ≤ 当前有效订购」，写 `SHIPMENT_CONSUME` 事实、
 * 消耗可发货、累加累计发货、冻结快照并追溯来源；**不生成任何库存流水**（库存已在领用时扣减）。
 * 作废写反向事实恢复可发货与累计发货，保留原确认快照；已关闭订单只能等量更正。
 */
@Service
public class ShipmentService {

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String ORDER_CONFIRMED = "CONFIRMED";
    private static final String ORDER_CLOSED = "CLOSED";
    private static final String ENTRY_CONSUME = "SHIPMENT_CONSUME";
    private static final String ENTRY_VOID = "SHIPMENT_VOID";
    private static final String SOURCE_SHIPMENT = "SHIPMENT";
    private static final String NODE_SHIPPABLE = "SHIPPABLE";

    private final ShipmentRepository repository;
    private final OrderRepository orderRepository;
    private final FulfillmentRepository fulfillmentRepository;
    private final FulfillmentLedger ledger;
    private final SequenceAllocator sequenceAllocator;
    private final AuditContext auditContext;

    public ShipmentService(ShipmentRepository repository, OrderRepository orderRepository,
                           FulfillmentRepository fulfillmentRepository, FulfillmentLedger ledger,
                           SequenceAllocator sequenceAllocator, AuditContext auditContext) {
        this.repository = repository;
        this.orderRepository = orderRepository;
        this.fulfillmentRepository = fulfillmentRepository;
        this.ledger = ledger;
        this.sequenceAllocator = sequenceAllocator;
        this.auditContext = auditContext;
    }

    // ---------- 查询 ----------

    public List<ShipmentViews.ShipmentView> list(long orderId) {
        requireOrder(orderId);
        return repository.findByOrder(orderId).stream().map(this::toView).toList();
    }

    public ShipmentViews.ShipmentView get(long shipmentId) {
        return toView(requireShipment(shipmentId));
    }

    // ---------- 草稿（任务 6.2） ----------

    @Transactional
    public ShipmentViews.ShipmentView createDraft(long orderId, ShipmentViews.ShipmentDraftRequest request) {
        var order = requireOrder(orderId);
        if (!ORDER_CONFIRMED.equals(order.status())) {
            throw new ApiException(ErrorCode.STATE_NOT_EDITABLE, "只有已确认订单可以创建发货草稿",
                    List.of(new ApiFieldError("orderId", "当前订单状态 " + order.status())));
        }
        var items = resolveItems(order, request, true);
        var audit = auditContext.current();
        var shipmentNo = SequenceAllocator.format("SH", sequenceAllocator.next("shipments"), 6);
        long id = repository.insert(shipmentNo, orderId, requireDate(request.shipmentDate()),
                request.carrier(), request.trackingNo(), freightOf(request.freight()), request.logisticsNote(),
                request.note(), audit.requestId());
        persistItems(id, order, items, audit.requestId());
        return get(id);
    }

    @Transactional
    public ShipmentViews.ShipmentView updateDraft(long shipmentId, ShipmentViews.ShipmentDraftRequest request) {
        var shipment = requireShipmentForUpdate(shipmentId);
        if (!STATUS_DRAFT.equals(shipment.status())) {
            throw new ApiException(ErrorCode.STATE_NOT_EDITABLE, "只有草稿批次可以编辑",
                    List.of(new ApiFieldError("shipmentId", "当前状态 " + shipment.status())));
        }
        var order = requireOrder(shipment.orderId());
        var items = resolveItems(order, request, true);
        var audit = auditContext.current();
        repository.updateDraft(shipmentId, requireDate(request.shipmentDate()), request.carrier(),
                request.trackingNo(), freightOf(request.freight()), request.logisticsNote(), request.note(),
                audit.requestId());
        repository.deleteItems(shipmentId);
        persistItems(shipmentId, order, items, audit.requestId());
        return get(shipmentId);
    }

    // ---------- 确认（任务 6.3/6.4） ----------

    @Transactional
    public ShipmentViews.ShipmentView confirm(long orderId, long shipmentId) {
        var order = requireOrder(orderId);
        // 先按明细 id 升序锁履约投影行，再锁批次行（施工文档 §8）
        var items = repository.findItems(shipmentId);
        if (items.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "发货批次没有明细",
                    List.of(new ApiFieldError("items", "至少一条发货明细")));
        }
        var locked = new LinkedHashMap<Long, ShipmentRepository.ItemBalanceRow>();
        for (var item : items) {
            locked.put(item.orderItemId(), repository.findBalanceForUpdate(item.orderItemId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "订单明细履约投影不存在")));
        }
        var shipment = requireShipmentForUpdate(shipmentId);
        if (shipment.orderId() != orderId || !STATUS_DRAFT.equals(shipment.status())) {
            throw new ApiException(ErrorCode.STATE_NOT_CONFIRMABLE, "只有草稿批次可以确认",
                    List.of(new ApiFieldError("shipmentId", "当前状态 " + shipment.status())));
        }
        var audit = auditContext.current();
        confirmInternal(order, shipment, items, locked, audit.requestId(), audit.adminUsername());
        return get(shipmentId);
    }

    // ---------- 物流修改（任务 6.5） ----------

    @Transactional
    public ShipmentViews.ShipmentView changeLogistics(long shipmentId,
                                                      ShipmentViews.LogisticsChangeRequest request) {
        var shipment = requireShipmentForUpdate(shipmentId);
        if (!STATUS_CONFIRMED.equals(shipment.status())) {
            throw new ApiException(ErrorCode.STATE_NOT_EDITABLE, "只有已确认批次可以修改物流",
                    List.of(new ApiFieldError("shipmentId", "当前状态 " + shipment.status())));
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "物流修改原因必填",
                    List.of(new ApiFieldError("reason", "请填写修改原因")));
        }
        var carrier = request.carrier() == null ? shipment.currentCarrier() : request.carrier();
        var trackingNo = request.trackingNo() == null ? shipment.currentTrackingNo() : request.trackingNo();
        var freight = request.freight() == null ? shipment.currentFreight() : request.freight();
        var note = request.logisticsNote() == null ? shipment.currentLogisticsNote() : request.logisticsNote();
        if (freight.compareTo(BigDecimal.ZERO) < 0) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "运费不得为负",
                    List.of(new ApiFieldError("freight", "运费不得为负")));
        }
        var audit = auditContext.current();
        repository.insertLogisticsChange(shipmentId, shipment.currentCarrier(), carrier,
                shipment.currentTrackingNo(), trackingNo, shipment.currentFreight(), freight,
                shipment.currentLogisticsNote(), note, request.reason(), audit.requestId());
        repository.updateCurrentLogistics(shipmentId, carrier, trackingNo, freight, note, audit.requestId());
        return get(shipmentId);
    }

    // ---------- 作废（任务 6.6） ----------

    @Transactional
    public ShipmentViews.ShipmentView voidShipment(long shipmentId, String reason) {
        var order = requireOrder(requireShipment(shipmentId).orderId());
        if (ORDER_CLOSED.equals(order.status())) {
            throw new ApiException(ErrorCode.STATE_CLOSED_REQUIRES_CORRECTION);
        }
        var shipment = requireShipmentForUpdate(shipmentId);
        if (!STATUS_CONFIRMED.equals(shipment.status())) {
            throw new ApiException(ErrorCode.STATE_NOT_CANCELABLE, "只有已确认批次可以作废",
                    List.of(new ApiFieldError("shipmentId", "当前状态 " + shipment.status())));
        }
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "作废原因必填",
                    List.of(new ApiFieldError("reason", "请填写作废原因")));
        }
        if (repository.hasAfterSalesOccupancy(shipmentId)) {
            // 作废会减少有效已发数量，使既有售后受理量失去依据（design.md §6 的 SHIPMENT_AFTER_SALES_LINKED）
            throw new ApiException(ErrorCode.SHIPMENT_AFTER_SALES_LINKED, "发货批次已被售后占用",
                    List.of(new ApiFieldError("shipmentId",
                            "批次 " + shipment.shipmentNo() + " 已被售后单占用，请先处理售后再作废")));
        }
        var audit = auditContext.current();
        voidInternal(order, shipment, reason, audit.requestId(), audit.adminUsername());
        return get(shipmentId);
    }

    // ---------- 等量更正（任务 6.7） ----------

    @Transactional
    public ShipmentViews.ShipmentView correct(long shipmentId, String reason) {
        var original = requireShipmentForUpdate(shipmentId);
        var order = requireOrder(original.orderId());
        if (!ORDER_CLOSED.equals(order.status())) {
            throw new ApiException(ErrorCode.STATE_NOT_EDITABLE, "只有已关闭订单需要等量更正",
                    List.of(new ApiFieldError("orderId", "未关闭订单请直接作废批次")));
        }
        // 先判「是否已更正过」再判状态：更正后原批次已变为 VOIDED，先报更具体的原因更利于管理员判断
        if (repository.existsCorrectionFor(shipmentId)) {
            throw new ApiException(ErrorCode.CONFLICT_DUPLICATE, "该批次已更正过",
                    List.of(new ApiFieldError("shipmentId", "一个原批次最多一次等量更正")));
        }
        if (!STATUS_CONFIRMED.equals(original.status())) {
            throw new ApiException(ErrorCode.STATE_NOT_EDITABLE, "只有已确认批次可以更正",
                    List.of(new ApiFieldError("shipmentId", "当前状态 " + original.status())));
        }
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "更正原因必填",
                    List.of(new ApiFieldError("reason", "请填写更正原因")));
        }
        var audit = auditContext.current();
        // 同一事务：先作废原批次（恢复可发货与累计发货），再创建等量替代批次并确认
        voidInternal(order, original, "等量更正：" + reason, audit.requestId(), audit.adminUsername());

        var originalItems = repository.findItems(shipmentId);
        var replacementNo = SequenceAllocator.format("SH", sequenceAllocator.next("shipments"), 6);
        long replacementId = repository.insert(replacementNo, order.id(), original.shipmentDate(),
                original.carrier(), original.trackingNo(), original.freight(), original.logisticsNote(),
                original.note(), audit.requestId());
        var locked = new LinkedHashMap<Long, ShipmentRepository.ItemBalanceRow>();
        for (var item : originalItems) {
            locked.put(item.orderItemId(), repository.findBalanceForUpdate(item.orderItemId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "订单明细履约投影不存在")));
        }
        for (var item : originalItems) {
            repository.insertItem(replacementId, item.orderItemId(), item.lineNo(), item.quantity(),
                    item.productNo(), item.productName(), item.recipientName(), item.recipientPhone(),
                    item.region(), item.address(), audit.requestId());
        }
        var replacement = requireShipment(replacementId);
        var replacementItems = repository.findItems(replacementId);
        try {
            confirmInternal(order, replacement, replacementItems, locked, audit.requestId(),
                    audit.adminUsername());
        } catch (ApiException failure) {
            // 可发货不足以立即创建等量替代：整笔回滚并引导走售后
            throw new ApiException(ErrorCode.CORRECTION_REPLACEMENT_REQUIRED,
                    "无法立即创建等量替代批次，请按售后流程处理",
                    List.of(new ApiFieldError("shipmentId", failure.getMessage())));
        }
        repository.markReplaces(replacementId, shipmentId, audit.requestId());
        repository.insertCorrection(shipmentId, replacementId, reason, audit.requestId());
        return get(replacementId);
    }

    // ---------- 内部 ----------

    private void confirmInternal(OrderRow order, ShipmentRepository.ShipmentRow shipment,
                                 List<ShipmentRepository.ShipmentItemRow> items,
                                 LinkedHashMap<Long, ShipmentRepository.ItemBalanceRow> locked,
                                 String requestId, String operatorUsername) {
        var errors = new ArrayList<ApiFieldError>();
        for (int index = 0; index < items.size(); index++) {
            var item = items.get(index);
            var balance = locked.get(item.orderItemId());
            var prefix = "items[" + index + "].quantity";
            if (item.quantity() > balance.shippableQuantity()) {
                errors.add(new ApiFieldError(prefix, "本次发货 " + item.quantity() + " 超过当前可发货 "
                        + balance.shippableQuantity()));
            }
            if (balance.shippedQuantity() + item.quantity() > balance.requiredQuantity()) {
                errors.add(new ApiFieldError(prefix, "累计有效发货将超过当前有效订购数量 "
                        + balance.requiredQuantity()));
            }
        }
        if (!errors.isEmpty()) {
            var code = errors.get(0).message().startsWith("本次发货")
                    ? ErrorCode.SHIPMENT_EXCEEDS_AVAILABLE
                    : ErrorCode.SHIPMENT_EXCEEDS_DEMAND;
            throw new ApiException(code, "发货数量超过允许上限", errors);
        }

        for (var item : items) {
            var balance = locked.get(item.orderItemId());
            ledger.registerOutflow(order.id(), item.orderItemId(), ENTRY_CONSUME, NODE_SHIPPABLE, item.quantity(),
                    SOURCE_SHIPMENT, shipment.id(), item.orderItemId(), shipment.shipmentDate(),
                    operatorUsername, "发货确认（" + shipment.shipmentNo() + "）", requestId);
            ledger.applyInflow(item.orderItemId(), NODE_SHIPPABLE, item.quantity(), false, requestId);
            ledger.applyShipped(item.orderItemId(), item.quantity(), true, requestId);
            int cumulative = balance.shippedQuantity() + item.quantity();
            repository.markItemConfirmed(item.id(), cumulative, balance.requiredQuantity() - cumulative,
                    requestId);
            linkSources(item, requestId);
        }
        repository.markConfirmed(shipment.id(), operatorUsername, requestId);
    }

    /** 来源追溯：按事实 id 升序把本次数量分摊到该明细的可发货入库来源；只追溯不改库存。 */
    private void linkSources(ShipmentRepository.ShipmentItemRow item, String requestId) {
        int remaining = item.quantity();
        for (var inflow : repository.findShippableInflows(item.orderItemId())) {
            if (remaining <= 0) {
                break;
            }
            int linked = Math.min(remaining, inflow.quantity());
            repository.insertSourceLink(item.id(), inflow.entryType(), inflow.sourceId(), 0, linked, requestId);
            remaining -= linked;
        }
    }

    private void voidInternal(OrderRow order, ShipmentRepository.ShipmentRow shipment, String reason,
                              String requestId, String operatorUsername) {
        var items = repository.findItems(shipment.id());
        for (var item : items) {
            var balance = repository.findBalanceForUpdate(item.orderItemId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "订单明细履约投影不存在"));
            ledger.registerInflow(order.id(), item.orderItemId(), ENTRY_VOID, NODE_SHIPPABLE, item.quantity(),
                    SOURCE_SHIPMENT, shipment.id(), item.orderItemId(), shipment.shipmentDate(),
                    operatorUsername, "发货作废（" + shipment.shipmentNo() + "）：" + reason, requestId);
            ledger.applyInflow(item.orderItemId(), NODE_SHIPPABLE, item.quantity(), true, requestId);
            ledger.applyShipped(item.orderItemId(), item.quantity(), false, requestId);
            // 作废不恢复原库存（需要恢复库存须另行取消领用并生成反向库存流水）
            if (balance.shippedQuantity() < item.quantity()) {
                throw new ApiException(ErrorCode.STATE_NOT_CANCELABLE, "累计有效发货不足，无法作废",
                        List.of(new ApiFieldError("shipmentId", "该批次可能已被更正或作废")));
            }
        }
        repository.markVoided(shipment.id(), operatorUsername, reason, requestId);
    }

    /** 明细解析：校验归属、数量与唯一性，并回填商品快照。 */
    private List<ItemSnapshot> resolveItems(OrderRow order, ShipmentViews.ShipmentDraftRequest request,
                                           boolean requirePositive) {
        if (request.items() == null || request.items().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "至少一条发货明细",
                    List.of(new ApiFieldError("items", "至少一条发货明细")));
        }
        var orderItems = new LinkedHashMap<Long, OrderItemRow>();
        for (var row : orderRepository.findItems(order.id())) {
            orderItems.put(row.id(), row);
        }
        var errors = new ArrayList<ApiFieldError>();
        var seen = new java.util.HashSet<Long>();
        var snapshots = new ArrayList<ItemSnapshot>();
        for (int index = 0; index < request.items().size(); index++) {
            var line = request.items().get(index);
            var prefix = "items[" + index + "]";
            if (line.orderItemId() == null) {
                errors.add(new ApiFieldError(prefix + ".orderItemId", "订单明细必填"));
                continue;
            }
            var row = orderItems.get(line.orderItemId());
            if (row == null) {
                errors.add(new ApiFieldError(prefix + ".orderItemId", "明细不属于该订单"));
                continue;
            }
            if (line.quantity() == null || line.quantity() < 1) {
                errors.add(new ApiFieldError(prefix + ".quantity", "数量必须大于 0"));
                continue;
            }
            if (!seen.add(line.orderItemId())) {
                errors.add(new ApiFieldError(prefix + ".orderItemId", "同一明细只能有一条发货明细"));
                continue;
            }
            snapshots.add(new ItemSnapshot(row, line.quantity()));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID,
                    ErrorCode.VALIDATION_INVALID.defaultMessage(), errors);
        }
        return snapshots;
    }

    private record ItemSnapshot(OrderItemRow row, int quantity) {
    }

    private void persistItems(long shipmentId, OrderRow order, List<ItemSnapshot> items, String requestId) {
        for (var item : items) {
            repository.insertItem(shipmentId, item.row().id(), item.row().lineNo(), item.quantity(),
                    item.row().productNo(), item.row().productName(), order.recipientName(),
                    order.recipientPhone(), order.region(), order.address(), requestId);
        }
    }

    private ShipmentViews.ShipmentView toView(ShipmentRepository.ShipmentRow row) {
        var items = repository.findItems(row.id()).stream()
                .map(item -> new ShipmentViews.ShipmentItemView(item.id(), item.orderItemId(), item.lineNo(),
                        item.productNo(), item.productName(), item.quantity(), item.recipientName(),
                        item.recipientPhone(), item.region(), item.address(), item.cumulativeShippedQuantity(),
                        item.undeliveredQuantity(),
                        repository.findSourceLinks(item.id()).stream()
                                .map(link -> new ShipmentViews.SourceLinkView(link.id(), link.sourceType(),
                                        link.sourceId(), link.sourceLineId(), link.quantity()))
                                .toList()))
                .toList();
        var changes = repository.findLogisticsChanges(row.id()).stream()
                .map(change -> new ShipmentViews.LogisticsChangeView(change.id(), change.beforeCarrier(),
                        change.afterCarrier(), change.beforeTrackingNo(), change.afterTrackingNo(),
                        change.beforeFreight(), change.afterFreight(), change.beforeNote(), change.afterNote(),
                        change.reason()))
                .toList();
        return new ShipmentViews.ShipmentView(row.id(), row.shipmentNo(), row.orderId(), row.status(),
                row.shipmentDate(), row.carrier(), row.trackingNo(), row.freight(), row.logisticsNote(),
                row.currentCarrier(), row.currentTrackingNo(), row.currentFreight(), row.currentLogisticsNote(),
                row.note(), row.confirmedBy(), row.voidReason(), row.replacesShipmentId(),
                repository.isAfterSalesReplacement(row.id()), row.version(), items, changes);
    }

    private OrderRow requireOrder(long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
    }

    private ShipmentRepository.ShipmentRow requireShipment(long shipmentId) {
        return repository.findById(shipmentId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "发货批次不存在"));
    }

    private ShipmentRepository.ShipmentRow requireShipmentForUpdate(long shipmentId) {
        return repository.findByIdForUpdate(shipmentId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "发货批次不存在"));
    }

    private static LocalDate requireDate(LocalDate date) {
        if (date == null) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "发货日期必填",
                    List.of(new ApiFieldError("shipmentDate", "请选择发货日期")));
        }
        return date;
    }

    private static BigDecimal freightOf(BigDecimal freight) {
        return freight == null ? BigDecimal.ZERO : freight;
    }
}

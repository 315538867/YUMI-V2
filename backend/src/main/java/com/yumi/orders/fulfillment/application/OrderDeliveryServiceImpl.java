package com.yumi.orders.fulfillment.application;

import com.yumi.orders.api.ConfirmShipmentCommand;
import com.yumi.orders.api.CorrectShipmentCommand;
import com.yumi.orders.api.OrderDeliveryService;
import com.yumi.orders.api.ShipmentItemRequest;
import com.yumi.orders.api.ShipmentRef;
import com.yumi.orders.application.port.OrderRepository;
import com.yumi.platform.api.audit.AuditService;
import com.yumi.platform.api.audit.RecordAuditCommand;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.http.RequestCorrelationHolder;
import com.yumi.platform.api.idempotency.CommandOutcome;
import com.yumi.platform.api.idempotency.CommandRequest;
import com.yumi.platform.api.idempotency.IdempotentCommandService;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.numbering.BusinessNumberService;
import com.yumi.production.api.ConsumeProductionCommand;
import com.yumi.production.api.ConsumptionPreview;
import com.yumi.production.api.ConsumptionResult;
import com.yumi.production.api.ProductionConsumptionParticipant;
import com.yumi.production.api.ProductionLinkRef;
import com.yumi.production.api.ReverseConsumptionCommand;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 发货与发货更正实现（基线 12 §7.3、§8.3、§10）。
 *
 * <p>锁序：订单根 → 约定组（稳定顺序）→ 生产来源（稳定顺序）。发货在订单根锁内完成，
 * 发货事实、来源消费与履约投影同事务提交；收货信息取自发货时的约定版本载荷，
 * 客户后续修改资料不回写历史发货。
 */
public class OrderDeliveryServiceImpl implements OrderDeliveryService {

    private static final String SHIP_SCOPE = "orders.fulfillment.ship";
    private static final String CORRECTION_SCOPE = "orders.fulfillment.correct_shipment";
    private static final String RESULT_TYPE = "SHIPMENT";
    private static final String DELIVERY_SOURCE_TYPE = "SHIPMENT";

    private final OrderRepository orders;
    private final ProductionConsumptionParticipant consumption;
    private final IdempotentCommandService idempotency;
    private final BusinessNumberService numbering;
    private final AuthorizationService authorization;
    private final CurrentActorProvider currentActor;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public OrderDeliveryServiceImpl(
            OrderRepository orders,
            ProductionConsumptionParticipant consumption,
            IdempotentCommandService idempotency,
            BusinessNumberService numbering,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            AuditService audit,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.consumption = consumption;
        this.idempotency = idempotency;
        this.numbering = numbering;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.audit = audit;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public ShipmentRef confirmShipment(ConfirmShipmentCommand command) {
        authorization.require(PermissionCodes.ORDER_SHIP, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_SHIPMENT", "发货命令不能为空");
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(SHIP_SCOPE, command.idempotencyKey(), shipFingerprint(command)),
                () -> new CommandOutcome(RESULT_TYPE, doShip(command))).outcome());
        return shipmentRef(outcome.resultBusinessId());
    }

    @Override
    public ShipmentRef correctShipment(CorrectShipmentCommand command) {
        authorization.require(PermissionCodes.ORDER_SHIP, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_CORRECTION", "更正命令不能为空");
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(
                        CORRECTION_SCOPE, command.idempotencyKey(), correctionFingerprint(command)),
                () -> new CommandOutcome(RESULT_TYPE, doCorrect(command))).outcome());
        return correctionRef(outcome.resultBusinessId(), command.quantityDecrease());
    }

    private String doShip(ConfirmShipmentCommand command) {
        Instant occurredAt = clock.instant();
        Instant shippedAt = command.shippedAt().toInstant(ZoneOffset.UTC);
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();

        orders.lockOrder(command.orderBusinessId());
        var order = orders.findOrder(command.orderBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在"));
        if (!"CONFIRMED".equals(order.status()) && !"IN_FULFILLMENT".equals(order.status())) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "ORDER_NOT_SHIPPABLE",
                    "只有已确认或履约中的订单可以发货");
        }

        List<ShipmentItemRequest> items = sortedItems(command.items());
        Map<String, GroupContext> groups = lockAndValidateGroups(order, items);
        validateSourceBelongsToListedGroup(groups, items);
        validateRemainingQuantities(groups, items);

        String shipmentId = "SHP-" + UUID.randomUUID();
        String shipmentNo = numbering.nextNumber("SHIPMENT", "SH", 6);
        List<PreparedShipmentItem> prepared = new ArrayList<>();
        for (ShipmentItemRequest item : items) {
            ConsumptionPreview preview = consumption.prepareConsume(new ConsumeProductionCommand(
                    new ProductionLinkRef("orders", DELIVERY_SOURCE_TYPE, shipmentId),
                    item.productionSourceBusinessId(), item.quantity()));
            prepared.add(new PreparedShipmentItem(item, preview));
        }

        String recipientJson = groups.values().iterator().next().version().recipientPayloadJson();
        orders.insertShipment(new OrderRepository.ShipmentRecord(
                shipmentId, shipmentNo, order.orderBusinessId(), shippedAt, recipientJson,
                command.trackingNo(), operatorId));

        Map<String, Integer> shippedByGroup = new LinkedHashMap<>();
        int shipped = 0;
        for (PreparedShipmentItem item : prepared) {
            ConsumptionResult consumed = consumption.commitConsume(item.preview());
            String shipmentItemId = "SHI-" + UUID.randomUUID();
            orders.insertShipmentItem(new OrderRepository.ShipmentItemRecord(
                    shipmentItemId, shipmentId, item.request().agreementGroupBusinessId(),
                    groups.get(item.request().agreementGroupBusinessId())
                            .version().agreementVersionBusinessId(),
                    item.request().productionSourceBusinessId(),
                    consumed.consumptionFactBusinessId(), item.request().quantity()));
            orders.insertShipmentSourceLink(
                    shipmentId, item.request().productionSourceBusinessId(),
                    consumed.consumptionFactBusinessId(), item.request().quantity());
            shippedByGroup.merge(
                    item.request().agreementGroupBusinessId(), item.request().quantity(), Integer::sum);
            shipped += item.request().quantity();
        }

        for (var entry : shippedByGroup.entrySet()) {
            GroupContext context = groups.get(entry.getKey());
            int fulfilled = context.group().fulfilledQuantity() + entry.getValue();
            orders.updateGroupFulfillment(
                    entry.getKey(), fulfilled,
                    fulfilled >= context.group().activeQuantity() ? "FULFILLED" : "ACTIVE");
        }

        var projection = orders.findProjection(order.orderBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "ORDER_PROJECTION_MISSING", "订单履约投影缺失"));
        int totalShipped = currentShipped(order.orderBusinessId());
        boolean deliveryFulfilled = totalShipped >= projection.requiredQuantity();
        writeProjection(order.orderBusinessId(), projection, totalShipped, deliveryFulfilled);
        orders.updateOrderDeliveryFulfilled(
                order.orderBusinessId(), deliveryFulfilled, projection.paymentFulfilled(),
                order.contentVersion());

        audit.record(new RecordAuditCommand(
                RequestCorrelationHolder.getOrUnknown(), operatorId, "orders", "CONFIRM_SHIPMENT",
                "SHIPMENT", shipmentId, "SUCCESS",
                Map.of("orderBusinessId", order.orderBusinessId(), "shipmentNo", shipmentNo,
                        "shippedQuantity", String.valueOf(shipped),
                        "deliveryFulfilled", String.valueOf(deliveryFulfilled)),
                occurredAt));
        return shipmentId;
    }

    private String doCorrect(CorrectShipmentCommand command) {
        Instant occurredAt = clock.instant();
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();

        var shipment = orders.findShipment(command.shipmentBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "SHIPMENT_NOT_FOUND", "发货记录不存在"));
        orders.lockOrder(shipment.orderBusinessId());
        var order = orders.findOrder(shipment.orderBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在"));

        var item = orders.findShipmentItem(command.shipmentItemBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "SHIPMENT_ITEM_NOT_FOUND", "发货明细不存在"));
        if (!item.shipmentBusinessId().equals(shipment.shipmentBusinessId())) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "SHIPMENT_ITEM_MISMATCH", "发货明细不属于该发货记录");
        }
        if (command.quantityDecrease() > item.quantity()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "SHIPMENT_CORRECTION_TOO_LARGE",
                    "更正数量不能超过该明细已发数量");
        }

        orders.lockGroup(item.agreementGroupBusinessId());
        // 反向失败（例如来源已移交线下）会直接抛出，发货事实保持原样
        var preview = consumption.prepareReverse(new ReverseConsumptionCommand(
                new ProductionLinkRef("orders", DELIVERY_SOURCE_TYPE, shipment.shipmentBusinessId()),
                item.productionSourceBusinessId(), command.quantityDecrease(), command.reasonText()));
        consumption.commitReverse(preview);

        orders.insertShipmentCorrection(new OrderRepository.CorrectionRecord(
                "SHC-" + UUID.randomUUID(), shipment.shipmentBusinessId(),
                item.shipmentItemBusinessId(), "QUANTITY_DECREASE", -command.quantityDecrease(),
                command.reasonText(), occurredAt, operatorId));
        orders.updateShipmentItemQuantity(
                item.shipmentItemBusinessId(), item.quantity() - command.quantityDecrease());

        var group = orders.findGroupById(item.agreementGroupBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "AGREEMENT_GROUP_NOT_FOUND", "交付约定组不存在"));
        int fulfilled = Math.max(group.fulfilledQuantity() - command.quantityDecrease(), 0);
        orders.updateGroupFulfillment(
                group.agreementGroupBusinessId(), fulfilled,
                "CANCELLED".equals(group.status())
                        ? "CANCELLED"
                        : (fulfilled >= group.activeQuantity() ? "FULFILLED" : "ACTIVE"));

        var projection = orders.findProjection(shipment.orderBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "ORDER_PROJECTION_MISSING", "订单履约投影缺失"));
        int totalShipped = currentShipped(shipment.orderBusinessId());
        boolean deliveryFulfilled = totalShipped >= projection.requiredQuantity();
        writeProjection(shipment.orderBusinessId(), projection, totalShipped, deliveryFulfilled);
        orders.updateOrderDeliveryFulfilled(
                shipment.orderBusinessId(), deliveryFulfilled, projection.paymentFulfilled(),
                order.contentVersion());

        audit.record(new RecordAuditCommand(
                RequestCorrelationHolder.getOrUnknown(), operatorId, "orders", "CORRECT_SHIPMENT",
                "SHIPMENT", shipment.shipmentBusinessId(), "SUCCESS",
                Map.of("shipmentItemBusinessId", item.shipmentItemBusinessId(),
                        "quantityDecrease", String.valueOf(command.quantityDecrease()),
                        "totalShippedQuantity", String.valueOf(totalShipped),
                        "deliveryFulfilled", String.valueOf(deliveryFulfilled)),
                occurredAt));
        return shipment.shipmentBusinessId();
    }

    private Map<String, GroupContext> lockAndValidateGroups(
            OrderRepository.OrderRecord order, List<ShipmentItemRequest> items) {
        Set<String> groupIds = new TreeSet<>();
        items.forEach(item -> groupIds.add(item.agreementGroupBusinessId()));

        Map<String, GroupContext> groups = new LinkedHashMap<>();
        for (String groupId : groupIds) {
            orders.lockGroup(groupId);
            var group = orders.findGroupById(groupId)
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.NOT_FOUND, "AGREEMENT_GROUP_NOT_FOUND", "交付约定组不存在"));
            var item = orders.findItem(group.orderItemBusinessId())
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.NOT_FOUND, "ORDER_ITEM_NOT_FOUND", "订单项不存在"));
            if (!item.orderBusinessId().equals(order.orderBusinessId())) {
                throw new ApiException(
                        ApiErrorType.VALIDATION, "SHIPMENT_GROUP_MISMATCH",
                        "交付约定组不属于该订单");
            }
            if ("CANCELLED".equals(group.status())) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "AGREEMENT_GROUP_CANCELLED",
                        "已取消的约定组不能发货");
            }
            var version = orders.findActiveVersion(
                            group.agreementGroupBusinessId(), group.activeVersionNo())
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.SYSTEM_FAILURE, "AGREEMENT_VERSION_MISSING",
                            "约定组缺少生效版本"));
            groups.put(groupId, new GroupContext(group, item, version));
        }
        return groups;
    }

    private void validateSourceBelongsToListedGroup(
            Map<String, GroupContext> groups, List<ShipmentItemRequest> items) {
        Set<String> sources = new HashSet<>();
        for (ShipmentItemRequest item : items) {
            if (!sources.add(item.productionSourceBusinessId())) {
                throw new ApiException(
                        ApiErrorType.VALIDATION, "SHIPMENT_DUPLICATE_SOURCE",
                        "同一发货不能重复消费同一生产来源");
            }
            GroupContext context = groups.get(item.agreementGroupBusinessId());
            String demandId = orders.findProductionDemandIdOfItem(
                    context.item().orderItemBusinessId());
            String expectedSource = orders.findProductionSourceIdOfDemand(demandId);
            if (!expectedSource.equals(item.productionSourceBusinessId())) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "SHIPMENT_SOURCE_MISMATCH",
                        "生产来源不属于该订单项的履约需求");
            }
        }
    }

    private void validateRemainingQuantities(
            Map<String, GroupContext> groups, List<ShipmentItemRequest> items) {
        Map<String, Integer> requested = new LinkedHashMap<>();
        items.forEach(item -> requested.merge(
                item.agreementGroupBusinessId(), item.quantity(), Integer::sum));
        for (var entry : requested.entrySet()) {
            GroupContext context = groups.get(entry.getKey());
            int remaining = context.group().activeQuantity() - context.group().fulfilledQuantity();
            if (entry.getValue() > remaining) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "SHIPMENT_QUANTITY_EXCEEDS_REMAINING",
                        "发货数量超过约定剩余可交付数量：剩余 " + remaining);
            }
        }
    }

    private void writeProjection(
            String orderBusinessId, OrderRepository.ProjectionRecord projection,
            int totalShipped, boolean deliveryFulfilled) {
        orders.insertFulfillmentProjection(new OrderRepository.ProjectionRecord(
                orderBusinessId, projection.requiredQuantity(), totalShipped,
                projection.currentReceivable(), projection.receivedAmount(), projection.refundedAmount(),
                deliveryFulfilled, projection.paymentFulfilled(),
                blockingReasons(deliveryFulfilled, projection.paymentFulfilled()), 1L));
    }

    private ShipmentRef correctionRef(String shipmentBusinessId, int quantityDecrease) {
        ShipmentRef current = shipmentRef(shipmentBusinessId);
        return new ShipmentRef(
                current.shipmentBusinessId(), current.shipmentNo(), current.orderBusinessId(),
                -quantityDecrease, current.totalShippedQuantity(), current.deliveryFulfilled());
    }

    private ShipmentRef shipmentRef(String shipmentBusinessId) {
        var shipment = orders.findShipment(shipmentBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "SHIPMENT_NOT_FOUND", "发货结果无法读取"));
        int shipmentQuantity = orders.findShipmentItems(shipmentBusinessId).stream()
                .mapToInt(OrderRepository.ShipmentItemRecord::quantity)
                .sum();
        int totalShipped = currentShipped(shipment.orderBusinessId());
        boolean deliveryFulfilled = orders.findProjection(shipment.orderBusinessId())
                .map(OrderRepository.ProjectionRecord::deliveryFulfilled)
                .orElse(false);
        return new ShipmentRef(
                shipment.shipmentBusinessId(), shipment.shipmentNo(), shipment.orderBusinessId(),
                shipmentQuantity, totalShipped, deliveryFulfilled);
    }

    private int currentShipped(String orderBusinessId) {
        return orders.findShipmentItemsOfOrder(orderBusinessId).stream()
                .mapToInt(OrderRepository.ShipmentItemRecord::quantity)
                .sum();
    }

    private static List<ShipmentItemRequest> sortedItems(List<ShipmentItemRequest> items) {
        return items.stream()
                .sorted(Comparator.comparing(ShipmentItemRequest::agreementGroupBusinessId)
                        .thenComparing(ShipmentItemRequest::productionSourceBusinessId))
                .toList();
    }

    private static String blockingReasons(boolean deliveryFulfilled, boolean paymentFulfilled) {
        String reasons = "";
        if (!deliveryFulfilled) {
            reasons = "DELIVERY_PENDING";
        }
        if (!paymentFulfilled) {
            reasons = reasons.isBlank() ? "PAYMENT_PENDING" : reasons + ",PAYMENT_PENDING";
        }
        return reasons.isBlank() ? null : reasons;
    }

    private static String shipFingerprint(ConfirmShipmentCommand command) {
        StringBuilder builder = new StringBuilder(command.orderBusinessId())
                .append('|').append(command.shippedAt())
                .append('|').append(command.trackingNo() == null ? "" : command.trackingNo());
        sortedItems(command.items()).forEach(item -> builder
                .append('|').append(item.agreementGroupBusinessId())
                .append(':').append(item.productionSourceBusinessId())
                .append(':').append(item.quantity()));
        return sha256(builder.toString());
    }

    private static String correctionFingerprint(CorrectShipmentCommand command) {
        return sha256(command.shipmentBusinessId() + "|" + command.shipmentItemBusinessId()
                + "|" + command.quantityDecrease() + "|" + command.reasonText());
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法", exception);
        }
    }

    private record GroupContext(
            OrderRepository.AgreementGroupRecord group,
            OrderRepository.ItemRecord item,
            OrderRepository.AgreementVersionRecord version) {
    }

    private record PreparedShipmentItem(
            ShipmentItemRequest request, ConsumptionPreview preview) {
    }
}

package com.yumi.orders.trade.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.customer.api.CustomerSnapshotRef;
import com.yumi.customer.api.CustomerSnapshotService;
import com.yumi.orders.api.IntakeLegacyOrderCommand;
import com.yumi.orders.api.LegacyOrderItemRequest;
import com.yumi.orders.api.OpeningOrderService;
import com.yumi.orders.api.OrderItemRequest;
import com.yumi.orders.api.OrderOrigin;
import com.yumi.orders.api.OrderRef;
import com.yumi.orders.api.OrderStatus;
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
import com.yumi.platform.api.value.ContentFingerprint;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.product.api.ProductCostBasisQuery;
import com.yumi.product.api.ProductSnapshotRef;
import com.yumi.product.api.ProductSnapshotService;
import com.yumi.production.api.CreateProductionDemandRequest;
import com.yumi.production.api.DemandType;
import com.yumi.production.api.InitializeProductionOpeningCommand;
import com.yumi.production.api.OpeningStageCode;
import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.ProductionBasis;
import com.yumi.production.api.ProductionDemandParticipant;
import com.yumi.production.api.ProductionOpeningApplicationService;
import com.yumi.production.api.ProductionOpeningLine;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 已有订单接管实现（基线 12 §8.4、11 §5.5）。
 *
 * <p>一次命令建立订单、约定与生产需求，并调用 production 完成期初初始化；
 * 只写接管时数量基线，不生成历史任务、劳动、材料成本或责任来源。
 */
public class OpeningOrderServiceImpl implements OpeningOrderService {

    private static final String SCOPE = "orders.trade.legacy_intake";
    private static final String RESULT_TYPE = "ORDER";

    private final OrderRepository orders;
    private final CustomerSnapshotService customerSnapshots;
    private final ProductSnapshotService productSnapshots;
    private final ProductCostBasisQuery costBasis;
    private final ProductionDemandParticipant productionDemands;
    private final ProductionOpeningApplicationService productionOpening;
    private final IdempotentCommandService idempotency;
    private final BusinessNumberService numbering;
    private final AuthorizationService authorization;
    private final CurrentActorProvider currentActor;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public OpeningOrderServiceImpl(
            OrderRepository orders,
            CustomerSnapshotService customerSnapshots,
            ProductSnapshotService productSnapshots,
            ProductCostBasisQuery costBasis,
            ProductionDemandParticipant productionDemands,
            ProductionOpeningApplicationService productionOpening,
            IdempotentCommandService idempotency,
            BusinessNumberService numbering,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            AuditService audit,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.customerSnapshots = customerSnapshots;
        this.productSnapshots = productSnapshots;
        this.costBasis = costBasis;
        this.productionDemands = productionDemands;
        this.productionOpening = productionOpening;
        this.idempotency = idempotency;
        this.numbering = numbering;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public OrderRef intake(IntakeLegacyOrderCommand command) {
        authorization.require(PermissionCodes.ORDER_LEGACY_INTAKE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_INTAKE", "接管命令不能为空");
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(SCOPE, command.idempotencyKey(), fingerprint(command)),
                () -> new CommandOutcome(RESULT_TYPE, doIntake(command))).outcome());
        return orderRef(outcome.resultBusinessId());
    }

    private String doIntake(IntakeLegacyOrderCommand command) {
        Instant now = clock.instant();
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
        CustomerSnapshotRef customerSnapshot = customerSnapshots.acquireCurrentCustomerSnapshot(
                command.customerBusinessId(), null);

        String orderBusinessId = "ORD-" + UUID.randomUUID();
        String orderNo = numbering.nextNumber("SALES_ORDER", "SO", 6);

        List<PreparedItem> prepared = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        int itemNo = 1;
        for (LegacyOrderItemRequest item : command.items()) {
            PreparedItem ready = prepareItem(item, itemNo++, orderBusinessId);
            prepared.add(ready);
            totalAmount = totalAmount.add(ready.amount().value());
        }
        int totalQuantity = prepared.stream()
                .mapToInt(entry -> entry.request().agreement().quantity())
                .sum();

        SettlementMoney receivable = SettlementMoney.of(totalAmount, CurrencyCode.CNY);
        orders.insertOrder(new OrderRepository.OrderRecord(
                orderBusinessId, orderNo, OrderOrigin.LEGACY_INTAKE.name(),
                command.customerBusinessId(), customerSnapshot.snapshotBusinessId(),
                command.orderDate(), command.expectedDeliveryDate(), command.note(),
                OrderStatus.CONFIRMED.name(), receivable.value(), java.math.BigDecimal.ZERO, "CNY",
                false, false, 1L, 1L, now, operatorId, now));
        orders.insertConfirmationFact(new OrderRepository.ConfirmationRecord(
                "OCF-" + UUID.randomUUID(), orderBusinessId, "LEGACY_INTAKE", totalQuantity,
                totalAmount, now, operatorId));
        orders.insertReceivableFact(new OrderRepository.ReceivableRecord(
                "ORF-" + UUID.randomUUID(), orderBusinessId, "ORIGINAL", totalAmount, "CNY", null, now));
        orders.insertFulfillmentProjection(new OrderRepository.ProjectionRecord(
                orderBusinessId, totalQuantity, 0, totalAmount, BigDecimal.ZERO, BigDecimal.ZERO,
                false, false, "DELIVERY_PENDING,PAYMENT_PENDING", 1L));

        String intakeId = "LOI-" + UUID.randomUUID();
        orders.insertLegacyIntake(new OrderRepository.LegacyIntakeRecord(
                intakeId, orderBusinessId, command.originalOrderReference(), command.note(),
                now, operatorId));

        for (PreparedItem entry : prepared) {
            writeItem(orderBusinessId, entry, now);
            orders.insertLegacyIntakeItem(
                    intakeId, entry.orderItemId(),
                    entry.request().agreement().quantity(), command.note());
            for (ProductionOpeningLine line : entry.request().openingLines()) {
                orders.insertLegacyStateLine(new OrderRepository.LegacyStateLineRecord(
                        intakeId, entry.orderItemId(), line.stageCode().name(),
                        qualityOf(line.stageCode()), dispositionOf(line.stageCode()),
                        destinationOf(line), line.reworkProcessCode() == null
                                ? null : line.reworkProcessCode().name(),
                        line.quantity(), line.reasonText()));
            }

            // sourceType=EXISTING_ORDER 会让 production 建立待期初来源
            var demand = productionDemands.commitCreate(
                    productionDemands.prepareCreate(entry.demandRequest()));
            productionOpening.initialize(new InitializeProductionOpeningCommand(
                    demand.demandBusinessId(), entry.request().openingLines()));
        }

        audit.record(new RecordAuditCommand(
                RequestCorrelationHolder.getOrUnknown(), operatorId, "orders", "INTAKE_LEGACY_ORDER",
                "ORDER", orderBusinessId, "SUCCESS",
                Map.of("orderNo", orderNo, "intakeId", intakeId,
                        "totalQuantity", String.valueOf(totalQuantity)),
                now));
        return orderBusinessId;
    }

    private PreparedItem prepareItem(
            LegacyOrderItemRequest legacy, int itemNo, String orderBusinessId) {
        OrderItemRequest item = legacy.agreement();
        var schemeSnapshot = productSnapshots.resolveScheme(item.schemeSnapshotRef());
        String productSnapshotRef = schemeSnapshot.productSnapshotRef().snapshotBusinessId();
        var basis = costBasis.resolveProductionBasis(
                item.schemeSnapshotRef(), item.targetProcess(),
                new ProductCostBasisQuery.MaterialSourceAgreement(
                        item.materialSources().glue(), item.materialSources().colorPaste(),
                        item.materialSources().bag(), item.materialSources().decoration()));
        SettlementMoney amount = SettlementMoney.of(
                item.unitPrice().value().multiply(BigDecimal.valueOf(item.quantity())),
                item.unitPrice().currency());

        String orderItemId = "OIT-" + UUID.randomUUID();
        String groupId = "AGR-" + UUID.randomUUID();
        String versionId = "AGV-" + UUID.randomUUID();
        Map<String, Object> recipientPayload = new LinkedHashMap<>();
        recipientPayload.put("customerName", item.recipient().customerName());
        recipientPayload.put("phone", item.recipient().phone());
        recipientPayload.put("address", item.recipient().address());

        ProductionBasis productionBasis = toProductionBasis(basis);
        return new PreparedItem(
                itemNo, orderItemId, groupId, versionId, legacy, amount,
                productSnapshotRef, item.schemeSnapshotRef(), recipientPayload, productionBasis,
                new CreateProductionDemandRequest(
                        "orders", "EXISTING_ORDER", orderItemId, orderBusinessId, orderItemId,
                        DemandType.NEW_PRODUCT, item.productBusinessId(), productSnapshotRef,
                        schemeSnapshot.schemeBusinessId(), item.schemeSnapshotRef().snapshotBusinessId(),
                        productionBasis, item.quantity(), null));
    }

    private void writeItem(String orderBusinessId, PreparedItem prepared, Instant now) {
        OrderItemRequest item = prepared.request().agreement();
        orders.insertItem(new OrderRepository.ItemRecord(
                prepared.orderItemId(), orderBusinessId, prepared.itemNo(),
                item.productBusinessId(), prepared.productSnapshotRef(), item.quantity(),
                item.quantity(), "ACTIVE", 1L));
        orders.insertAgreementGroup(new OrderRepository.AgreementGroupRecord(
                prepared.groupId(), prepared.orderItemId(), 1, item.quantity(), 0, "ACTIVE", 1L));
        orders.insertAgreementVersion(new OrderRepository.AgreementVersionRecord(
                prepared.versionId(), prepared.groupId(), 1,
                item.schemeSnapshotRef().snapshotBusinessId(), item.targetProcess().name(),
                item.unitPrice().value(), item.quantity(),
                item.materialSources().glue().name(), item.materialSources().colorPaste().name(),
                item.materialSources().bag().name(), item.materialSources().decoration().name(),
                writeJson(basisPayload(prepared.productionBasis())),
                prepared.demandRequest().basis().fingerprint().value(),
                writeJson(prepared.recipientPayload()), null, prepared.versionId(),
                ContentFingerprint.of(sha256(prepared.versionId()
                        + item.schemeSnapshotRef().snapshotBusinessId() + item.quantity())).value(),
                now));

        Map<String, BigDecimal[]> aggregated = new LinkedHashMap<>();
        for (var line : prepared.productionBasis().costLines()) {
            String processCode = line.processCode() == null ? "EXTRA" : line.processCode().name();
            BigDecimal[] values = aggregated.computeIfAbsent(
                    processCode, key -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            if ("LABOR".equals(line.nature())) {
                values[0] = values[0].add(line.fullResourceCost().value());
            } else {
                values[1] = values[1].add(line.fullResourceCost().value());
            }
        }
        for (var entry : aggregated.entrySet()) {
            orders.insertAgreementProcess(new OrderRepository.AgreementProcessRecord(
                    prepared.versionId(), entry.getKey(),
                    standardMinutesOf(prepared.productionBasis(), prepared.productionBasis().targetProcess()),
                    entry.getValue()[0], entry.getValue()[1], BigDecimal.ZERO, null));
        }
    }

    private OrderRef orderRef(String orderBusinessId) {
        var record = orders.findOrder(orderBusinessId).orElseThrow();
        int quantity = orders.findItems(orderBusinessId).stream()
                .mapToInt(OrderRepository.ItemRecord::activeQuantity).sum();
        return new OrderRef(
                record.orderBusinessId(), record.orderNo(), OrderStatus.valueOf(record.status()),
                quantity, SettlementMoney.of(record.currentReceivableAmount(), CurrencyCode.CNY),
                record.contentVersion(), record.rowVersion());
    }

    private static int standardMinutesOf(ProductionBasis basis, com.yumi.production.api.TargetProcess process) {
        return switch (process) {
            case MAKING -> basis.makingStandardMinutes();
            case BAGGING -> basis.baggingStandardMinutes();
            case SEAM_CUTTING -> basis.seamStandardMinutes();
        };
    }

    private static Map<String, Object> basisPayload(ProductionBasis basis) {
        return Map.of(
                "fingerprint", basis.fingerprint().value(),
                "targetProcess", basis.targetProcess().name(),
                "makingStandardMinutes", basis.makingStandardMinutes(),
                "baggingStandardMinutes", basis.baggingStandardMinutes(),
                "seamStandardMinutes", basis.seamStandardMinutes());
    }

    private static String qualityOf(OpeningStageCode stageCode) {
        return switch (stageCode) {
            case NOT_STARTED -> "NONE";
            case REWORK_REQUIRED, SCRAPPED -> "UNQUALIFIED";
            default -> "QUALIFIED";
        };
    }

    private static String dispositionOf(OpeningStageCode stageCode) {
        return switch (stageCode) {
            case REWORK_REQUIRED -> "REWORK";
            case SCRAPPED -> "SCRAP";
            default -> "";
        };
    }

    private static String destinationOf(ProductionOpeningLine line) {
        return line.stageCode() == OpeningStageCode.SCRAPPED
                ? line.physicalDestination().name() : PhysicalDestination.IN_SYSTEM.name();
    }

    private static ProductionBasis toProductionBasis(ProductCostBasisQuery.ResolvedProductionBasis basis) {
        return new ProductionBasis(
                basis.schemeSnapshotRef().snapshotBusinessId(),
                basis.productSnapshotRef().snapshotBusinessId(), basis.schemeBusinessId(),
                com.yumi.production.api.TargetProcess.valueOf(basis.targetProcess().name()),
                new ProductionBasis.MaterialSources(
                        basis.materialSources().glue(), basis.materialSources().colorPaste(),
                        basis.materialSources().bag(), basis.materialSources().decoration()),
                basis.costLines().stream().map(line -> new ProductionBasis.CostLine(
                        line.componentCode(),
                        line.processCode() == null ? null
                                : com.yumi.production.api.TargetProcess.valueOf(line.processCode()),
                        line.nature(), line.source(), line.quantity(), line.unitPrice(),
                        line.fullResourceCost())).toList(),
                basis.formulaRuleVersion(), basis.roundingVersion(), basis.contentFingerprint(),
                basis.makingStandardMinutes(), basis.baggingStandardMinutes(),
                basis.seamStandardMinutes());
    }

    private String fingerprint(IntakeLegacyOrderCommand command) {
        StringBuilder builder = new StringBuilder(command.customerBusinessId())
                .append('|').append(command.orderDate()).append('|').append(command.expectedDeliveryDate());
        for (LegacyOrderItemRequest item : command.items()) {
            OrderItemRequest agreement = item.agreement();
            builder.append('|').append(agreement.productBusinessId())
                    .append(':').append(agreement.schemeSnapshotRef().snapshotBusinessId())
                    .append(':').append(agreement.targetProcess())
                    .append(':').append(agreement.quantity())
                    .append(':').append(agreement.unitPrice().value().toPlainString());
            for (ProductionOpeningLine line : item.openingLines()) {
                builder.append(';').append(line.stageCode()).append(':').append(line.quantity())
                        .append(':').append(line.physicalDestination())
                        .append(':').append(line.reworkProcessCode());
            }
        }
        return sha256(builder.toString());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "ORDER_PAYLOAD_INVALID", "订单约定内容无法序列化");
        }
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

    private record PreparedItem(
            int itemNo, String orderItemId, String groupId, String versionId,
            LegacyOrderItemRequest request, SettlementMoney amount, String productSnapshotRef,
            ProductSnapshotRef schemeRef, Map<String, Object> recipientPayload,
            ProductionBasis productionBasis, CreateProductionDemandRequest demandRequest) {
    }
}

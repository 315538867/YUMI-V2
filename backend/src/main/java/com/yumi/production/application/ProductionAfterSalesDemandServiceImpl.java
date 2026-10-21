package com.yumi.production.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.value.ContentFingerprint;
import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.product.api.MaterialSource;
import com.yumi.production.api.AfterSalesRepairDemandRef;
import com.yumi.production.api.ChangeRepairRouteCommand;
import com.yumi.production.api.CreateAfterSalesRepairDemandCommand;
import com.yumi.production.api.CreateProductionDemandRequest;
import com.yumi.production.api.DemandType;
import com.yumi.production.api.ProductionAfterSalesDemandParticipant;
import com.yumi.production.api.ProductionBasis;
import com.yumi.production.api.ProductionDemandParticipant;
import com.yumi.production.api.RepairRoutePreview;
import com.yumi.production.api.RepairRouteResult;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.application.port.DemandRecord;
import com.yumi.production.application.port.ProductionRepository;
import com.yumi.production.application.port.QuantityProjections;
import com.yumi.production.application.port.ReturnedProductRepository;
import com.yumi.production.application.port.SourceRecord;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 售后修复需求与转路线实现（基线 11 §5.5、§7.9）。
 *
 * <p>修复需求沿用退回制品原始物理根与历史生产依据；转路线只减少当前缺口，
 * 不创建任务、劳动或补发需求。
 */
public class ProductionAfterSalesDemandServiceImpl
        implements ProductionAfterSalesDemandParticipant {

    private static final String REPAIR_SOURCE_TYPE = "AFTER_SALES_REPAIR";

    private final ProductionRepository production;
    private final ReturnedProductRepository returns;
    private final ProductionDemandParticipant demands;
    private final ObjectMapper objectMapper;

    public ProductionAfterSalesDemandServiceImpl(
            ProductionRepository production,
            ReturnedProductRepository returns,
            ProductionDemandParticipant demands,
            ObjectMapper objectMapper) {
        this.production = production;
        this.returns = returns;
        this.demands = demands;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AfterSalesRepairDemandRef createRepairDemand(
            CreateAfterSalesRepairDemandCommand command) {
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_REPAIR_DEMAND", "修复需求命令不能为空");
        }
        var returned = returns.lockReturnedSource(command.returnedSourceBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_RETURNED_SOURCE_NOT_FOUND",
                        "退回制品来源不存在"));
        var existing = production.findDemandBySource(
                "orders", REPAIR_SOURCE_TYPE, command.returnedSourceBusinessId());
        if (existing.isPresent()) {
            var source = production.findSourcesOfDemand(existing.get().demandBusinessId()).getFirst();
            return new AfterSalesRepairDemandRef(
                    existing.get().demandBusinessId(), source.productionSourceBusinessId(),
                    returned.returnedSourceBusinessId(), returned.originalQuantity(),
                    returned.availableQuantity());
        }
        if (returned.availableQuantity() < command.quantity()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_QUANTITY_EXCEEDED",
                    "退回制品可用数量不足：可用 " + returned.availableQuantity());
        }
        SourceRecord originalRoot = production.findSource(returned.physicalRootSourceBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_RETURN_ROOT_REQUIRED",
                        "退回制品原始物理根不存在"));
        DemandRecord originalDemand = production.findDemand(originalRoot.demandBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_DEMAND_NOT_FOUND",
                        "原始生产需求不存在"));
        ProductionBasis basis = readBasis(originalDemand.productionBasisJson());

        var created = demands.commitCreate(demands.prepareCreate(
                new CreateProductionDemandRequest(
                        "orders", REPAIR_SOURCE_TYPE, returned.returnedSourceBusinessId(),
                        originalDemand.orderBusinessId(), null,
                        DemandType.RETURNED_PRODUCT_REPAIR, returned.productSnapshotRef(),
                        returned.productSnapshotRef(), originalDemand.schemeBusinessId(),
                        returned.schemeSnapshotRef(), basis, command.quantity(),
                        returned.returnedSourceBusinessId())));
        returns.updateReturnedSourceQuantities(
                returned.returnedSourceBusinessId(),
                returned.availableQuantity() - command.quantity(),
                returned.routedRepairQuantity() + command.quantity(),
                returned.disposedQuantity(),
                returned.availableQuantity() - command.quantity() == 0 ? "ROUTED" : "AVAILABLE");
        return new AfterSalesRepairDemandRef(
                created.demandBusinessId(), created.productionSourceBusinessId(),
                returned.returnedSourceBusinessId(), command.quantity(),
                returned.availableQuantity() - command.quantity());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public com.yumi.production.api.AfterSalesReplacementDemandRef createReplacementDemand(
            com.yumi.production.api.CreateAfterSalesReplacementDemandCommand command) {
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_REPLACEMENT", "补发需求命令不能为空");
        }
        DemandRecord repair = production.findDemand(command.repairDemandBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_DEMAND_NOT_FOUND", "修复需求不存在"));
        if (repair.demandType() != DemandType.RETURNED_PRODUCT_REPAIR) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_REPAIR_ROUTE_CHANGE_BLOCKED",
                    "补发必须引用修复需求");
        }
        var existing = production.findDemandBySource(
                "orders", "AFTER_SALES_RESHIP", command.successorObligationBusinessId());
        if (existing.isPresent()) {
            var source = production.findSourcesOfDemand(existing.get().demandBusinessId()).getFirst();
            return new com.yumi.production.api.AfterSalesReplacementDemandRef(
                    existing.get().demandBusinessId(), source.productionSourceBusinessId(),
                    existing.get().requiredQuantity());
        }
        ProductionBasis basis = readBasis(repair.productionBasisJson());
        var created = demands.commitCreate(demands.prepareCreate(
                new CreateProductionDemandRequest(
                        "orders", "AFTER_SALES_RESHIP", command.successorObligationBusinessId(),
                        repair.orderBusinessId(), null,
                        DemandType.NEW_PRODUCT, repair.productBusinessId(),
                        repair.productSnapshotRef(), repair.schemeBusinessId(),
                        repair.schemeSnapshotRef(), basis, command.quantity(), null)));
        return new com.yumi.production.api.AfterSalesReplacementDemandRef(
                created.demandBusinessId(), created.productionSourceBusinessId(), command.quantity());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RepairRoutePreview previewRouteChange(ChangeRepairRouteCommand command) {
        DemandContext context = lockContext(command.repairDemandBusinessId());
        var projection = production.findDemandProjection(context.demand().demandBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_PROJECTION_MISSING", "需求数量投影缺失"));
        String blocking = command.quantity() > projection.fulfillmentGapQuantity()
                ? "当前修复缺口仅 " + projection.fulfillmentGapQuantity() : null;
        return new RepairRoutePreview(
                context.demand().demandBusinessId(), context.source().productionSourceBusinessId(),
                projection.fulfillmentGapQuantity(), command.quantity(), blocking == null, blocking);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public com.yumi.production.api.RepairDemandState describeRepairDemand(
            String repairDemandBusinessId) {
        DemandContext context = lockContext(repairDemandBusinessId);
        var projection = production.findDemandProjection(context.demand().demandBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_PROJECTION_MISSING", "需求数量投影缺失"));
        return new com.yumi.production.api.RepairDemandState(
                context.demand().demandBusinessId(), context.source().productionSourceBusinessId(),
                projection.requiredQuantity(), projection.pendingWorkQuantity(),
                projection.inProcessQuantity(), projection.fulfillmentGapQuantity(),
                projection.fulfilledQuantity());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RepairRouteResult commitRouteChange(ChangeRepairRouteCommand command) {
        RepairRoutePreview preview = previewRouteChange(command);
        if (!preview.allowed()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_REPAIR_ROUTE_CHANGE_BLOCKED",
                    "修复失败转路线被拒绝：" + preview.blockingReason());
        }
        DemandContext context = lockContext(command.repairDemandBusinessId());
        var projection = production.findDemandProjection(context.demand().demandBusinessId())
                .orElseThrow();
        int gapAfter = projection.fulfillmentGapQuantity() - command.quantity();
        int requiredAfter = projection.requiredQuantity() - command.quantity();
        if (gapAfter < 0 || requiredAfter < 0) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_PROJECTION_INCONSISTENT",
                    "修复转路线导致数量为负");
        }
        production.insertDemandProjection(new QuantityProjections.DemandProjection(
                context.demand().demandBusinessId(), requiredAfter,
                projection.pendingWorkQuantity(), projection.inProcessQuantity(),
                projection.targetReadyQuantity(), projection.fulfilledQuantity(), gapAfter,
                projection.rowVersion() + 1));
        production.updateDemandRequiredQuantity(context.demand().demandBusinessId(), requiredAfter);
        production.insertDemandChangeFact(new ProductionRepository.DemandChangeFact(
                "DCF-" + UUID.randomUUID(), context.demand().demandBusinessId(),
                context.source().productionSourceBusinessId(), "AFTER_SALES_ROUTE_CHANGE",
                projection.requiredQuantity(), requiredAfter, -command.quantity(), 0, 0, 0,
                command.quantity(), "AFTER_SALES_ROUTE_CHANGE", "修复失败转路线"));
        return new RepairRouteResult(
                context.demand().demandBusinessId(), context.source().productionSourceBusinessId(),
                command.quantity(), projection.fulfillmentGapQuantity(), gapAfter, requiredAfter);
    }

    private DemandContext lockContext(String demandBusinessId) {
        DemandRecord demand = production.findDemand(demandBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_DEMAND_NOT_FOUND", "生产需求不存在"));
        if (demand.demandType() != DemandType.RETURNED_PRODUCT_REPAIR) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_REPAIR_ROUTE_CHANGE_BLOCKED",
                    "只有修复需求可以转换缺口");
        }
        SourceRecord source = production.findSourcesOfDemand(demandBusinessId).stream()
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_SOURCE_NOT_FOUND", "修复来源不存在"));
        return new DemandContext(demand, production.lockSource(source.productionSourceBusinessId()));
    }

    private ProductionBasis readBasis(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            List<ProductionBasis.CostLine> costLines = new ArrayList<>();
            for (JsonNode line : node.path("costLines")) {
                JsonNode process = line.path("processCode");
                costLines.add(new ProductionBasis.CostLine(
                        line.path("componentCode").asText(),
                        process.isNull() || process.isMissingNode() || process.asText().isBlank()
                                ? null : TargetProcess.valueOf(process.asText()),
                        line.path("nature").asText(),
                        MaterialSource.valueOf(line.path("source").asText()),
                        line.path("quantity").isMissingNode() || line.path("quantity").isNull()
                                ? null : new BigDecimal(line.path("quantity").asText()),
                        line.path("unitPrice").isMissingNode() || line.path("unitPrice").isNull()
                                ? null : CostAmount.of(new BigDecimal(line.path("unitPrice").asText()),
                                        CurrencyCode.CNY),
                        CostAmount.of(new BigDecimal(line.path("fullResourceCost").asText()),
                                CurrencyCode.CNY)));
            }
            return new ProductionBasis(
                    node.path("schemeSnapshotRef").asText(),
                    node.path("productSnapshotRef").asText(),
                    node.path("schemeBusinessId").asText(),
                    TargetProcess.valueOf(node.path("targetProcess").asText()),
                    new ProductionBasis.MaterialSources(
                            MaterialSource.valueOf(node.path("glueSource").asText()),
                            MaterialSource.valueOf(node.path("colorPasteSource").asText()),
                            MaterialSource.valueOf(node.path("bagSource").asText()),
                            MaterialSource.valueOf(node.path("decorationSource").asText())),
                    costLines,
                    node.path("formulaRuleVersion").asText(),
                    node.path("roundingVersion").asText(),
                    ContentFingerprint.of(node.path("fingerprint").asText()),
                    node.path("makingStandardMinutes").asInt(),
                    node.path("baggingStandardMinutes").asInt(),
                    node.path("seamStandardMinutes").asInt());
        } catch (Exception exception) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_BASIS_INVALID",
                    "原始生产依据无法解析：" + exception.getMessage());
        }
    }

    private record DemandContext(DemandRecord demand, SourceRecord source) {
    }
}

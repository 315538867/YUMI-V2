package com.yumi.production.application;

import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.production.api.DemandType;
import com.yumi.production.api.InitializeProductionOpeningCommand;
import com.yumi.production.api.OpeningStageCode;
import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.ProductionOpeningApplicationService;
import com.yumi.production.api.ProductionOpeningLine;
import com.yumi.production.api.ProductionOpeningRef;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.application.port.DemandRecord;
import com.yumi.production.application.port.ProductionRepository;
import com.yumi.production.application.port.QuantityProjections;
import com.yumi.production.application.port.SourceRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 已有订单生产来源的一次性期初初始化（基线 11 §5.5、§7.9）。
 *
 * <p>只把当前数量分布到未制、在制、目标就绪、待返工与报废状态；不创建历史任务、
 * 执行人员、劳动、材料成本或责任来源。
 */
public class ProductionOpeningServiceImpl implements ProductionOpeningApplicationService {

    private static final String REQUIRES_OPENING = "REQUIRES_OPENING";
    private static final String PENDING_OPENING = "PENDING_OPENING";
    private static final String OPENED = "OPENED";
    private static final String ACTIVE = "ACTIVE";

    private final ProductionRepository production;
    private final CurrentActorProvider currentActor;
    private final Clock clock;

    public ProductionOpeningServiceImpl(
            ProductionRepository production, CurrentActorProvider currentActor, Clock clock) {
        this.production = production;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProductionOpeningRef initialize(InitializeProductionOpeningCommand command) {
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_OPENING", "期初初始化命令不能为空");
        }
        DemandRecord demand = production.findDemand(command.demandBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_DEMAND_NOT_FOUND", "生产需求不存在"));
        if (demand.demandType() != DemandType.NEW_PRODUCT) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_OPENING_UNSUPPORTED",
                    "一期期初接管只支持新制来源");
        }
        SourceRecord source = production.lockSource(
                production.findSourcesOfDemand(demand.demandBusinessId()).stream()
                        .findFirst()
                        .orElseThrow(() -> new ApiException(
                                ApiErrorType.NOT_FOUND, "PRODUCTION_SOURCE_NOT_FOUND", "履约来源不存在"))
                        .productionSourceBusinessId());
        if (!REQUIRES_OPENING.equals(source.openingStatus())
                && !PENDING_OPENING.equals(source.openingStatus())) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_OPENING_ALREADY_DONE",
                    "该来源已经完成期初初始化");
        }
        if (production.countOpeningFactsOfSource(source.productionSourceBusinessId()) > 0) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_OPENING_ALREADY_DONE",
                    "该来源已经存在期初事实");
        }

        int total = command.lines().stream().mapToInt(ProductionOpeningLine::quantity).sum();
        if (total != demand.requiredQuantity()) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCTION_OPENING_QUANTITY_MISMATCH",
                    "期初状态合计必须等于来源接管数量：合计 " + total
                            + "，接管 " + demand.requiredQuantity());
        }

        int pending = 0;
        int inProcess = 0;
        int targetReady = 0;
        int gap = 0;
        int destroyed = 0;
        int offline = 0;
        String batchId = "OPB-" + UUID.randomUUID();
        Instant now = clock.instant();
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();

        for (ProductionOpeningLine line : command.lines()) {
            TargetProcess process = processOf(line.stageCode());
            if (process != null && !source.targetProcess().chainTo().contains(process)) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_OPENING_STAGE_INVALID",
                        "期初工序不在目标工序链内：" + process + " 晚于 " + source.targetProcess());
            }
            if (line.stageCode() == OpeningStageCode.REWORK_REQUIRED
                    && !source.targetProcess().chainTo().contains(line.reworkProcessCode())) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_OPENING_STAGE_INVALID",
                        "返工工序不在目标工序链内：" + line.reworkProcessCode());
            }

            switch (line.stageCode()) {
                case NOT_STARTED -> pending += line.quantity();
                case REWORK_REQUIRED -> inProcess += line.quantity();
                case SCRAPPED -> {
                    gap += line.quantity();
                    if (line.physicalDestination() == PhysicalDestination.DESTROYED) {
                        destroyed += line.quantity();
                    } else {
                        offline += line.quantity();
                    }
                }
                case MAKING, BAGGING, SEAM_CUTTING -> {
                    if (process == source.targetProcess()) {
                        targetReady += line.quantity();
                    } else {
                        inProcess += line.quantity();
                    }
                }
            }

            production.insertOpeningFact(new ProductionRepository.OpeningFact(
                    "OPF-" + UUID.randomUUID(), batchId, demand.demandBusinessId(),
                    source.productionSourceBusinessId(), line.stageCode().name(),
                    qualityOf(line.stageCode()), dispositionOf(line.stageCode()),
                    destinationOf(line), line.reworkProcessCode() == null
                            ? null : line.reworkProcessCode().name(),
                    line.quantity(), reasonCodeOf(line.stageCode()), line.reasonText(),
                    now, operatorId));

            if (line.stageCode() != OpeningStageCode.NOT_STARTED
                    && line.stageCode() != OpeningStageCode.SCRAPPED) {
                production.insertStageProjection(new ProductionRepository.StageProjection(
                        source.productionSourceBusinessId(), source.physicalRootSourceBusinessId(),
                        line.stageCode() == OpeningStageCode.REWORK_REQUIRED
                                ? "REWORK_REQUIRED" : line.stageCode().name(),
                        qualityOf(line.stageCode()), dispositionOf(line.stageCode()),
                        "IN_SYSTEM", line.quantity()));
            }
        }

        production.insertDemandProjection(new QuantityProjections.DemandProjection(
                demand.demandBusinessId(), demand.requiredQuantity(), pending, inProcess,
                targetReady, 0, gap, 1L));
        production.insertPhysicalProjection(new QuantityProjections.PhysicalProjection(
                source.physicalRootSourceBusinessId(), demand.requiredQuantity(),
                demand.requiredQuantity() - gap, 0, destroyed, offline, 1L));
        production.updateSourceOpening(source.productionSourceBusinessId(), OPENED, ACTIVE);

        return new ProductionOpeningRef(
                demand.demandBusinessId(), source.productionSourceBusinessId(), batchId, total);
    }

    private static TargetProcess processOf(OpeningStageCode stageCode) {
        return switch (stageCode) {
            case MAKING -> TargetProcess.MAKING;
            case BAGGING -> TargetProcess.BAGGING;
            case SEAM_CUTTING -> TargetProcess.SEAM_CUTTING;
            default -> null;
        };
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
                ? line.physicalDestination().name() : "IN_SYSTEM";
    }

    private static String reasonCodeOf(OpeningStageCode stageCode) {
        return switch (stageCode) {
            case REWORK_REQUIRED -> "REWORK_REQUIRED";
            case SCRAPPED -> "SCRAPPED";
            default -> null;
        };
    }
}

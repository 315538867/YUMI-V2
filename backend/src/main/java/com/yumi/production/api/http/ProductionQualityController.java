package com.yumi.production.api.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.api.http.RequestContext;
import com.yumi.platform.api.idempotency.CommandOutcome;
import com.yumi.platform.api.idempotency.CommandRequest;
import com.yumi.platform.api.http.RequestContextResolver;
import com.yumi.platform.api.idempotency.CommandExecutionFacade;
import com.yumi.production.api.ChangeDefectReasonStatusCommand;
import com.yumi.production.api.CorrectResponsibilityCommand;
import com.yumi.production.api.DefectReasonAdministrationService;
import com.yumi.production.api.DefectReasonQuery;
import com.yumi.production.api.DefectReasonStatus;
import com.yumi.production.api.DefectReasonView;
import com.yumi.production.api.DispositionQuery;
import com.yumi.production.api.DispositionView;
import com.yumi.production.api.ProductionCorrectionCommand;
import com.yumi.production.api.ProductionCorrectionResult;
import com.yumi.production.api.ProductionCorrectionService;
import com.yumi.production.api.ResponsibilityApplicationService;
import com.yumi.production.api.ResponsibilityView;
import com.yumi.production.api.SaveDefectReasonCommand;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 生产质量 HTTP 适配器（交付清单 N02）。
 *
 * <p>只承载已核验事实更正、报废责任独立更正、缺陷原因维护与处置查询；售后池初次处置
 * 属于订单 A01，不在此暴露。
 */
@RestController
@RequestMapping("/api/production/quality")
public class ProductionQualityController {

    private final ProductionCorrectionService corrections;

    private final ResponsibilityApplicationService responsibilities;

    private final DefectReasonAdministrationService defectReasons;

    private final DefectReasonQuery defectReasonQuery;

    private final DispositionQuery dispositionQuery;

    private final CommandExecutionFacade commands;

    private final RequestContextResolver contextResolver;

    private final ObjectMapper objectMapper;

    public ProductionQualityController(
            ProductionCorrectionService corrections,
            ResponsibilityApplicationService responsibilities,
            DefectReasonAdministrationService defectReasons,
            DefectReasonQuery defectReasonQuery,
            DispositionQuery dispositionQuery,
            CommandExecutionFacade commands,
            RequestContextResolver contextResolver,
            ObjectMapper objectMapper) {
        this.corrections = corrections;
        this.responsibilities = responsibilities;
        this.defectReasons = defectReasons;
        this.defectReasonQuery = defectReasonQuery;
        this.dispositionQuery = dispositionQuery;
        this.commands = commands;
        this.contextResolver = contextResolver;
        this.objectMapper = objectMapper;
    }

    /** 缺陷原因状态变更请求体。 */
    public record ChangeDefectReasonStatusRequest(
            DefectReasonStatus status, long expectedRowVersion) {
    }

    @GetMapping("/defect-reasons")
    public List<DefectReasonView> defectReasons(
            @RequestParam(defaultValue = "false") boolean activeOnly) {
        return activeOnly ? defectReasonQuery.listActive() : defectReasonQuery.listAll();
    }

    @PostMapping("/defect-reasons")
    public ResponseEntity<DefectReasonView> saveDefectReason(
            @RequestBody SaveDefectReasonCommand command, HttpServletRequest servletRequest) {
        RequestContext context = contextResolver.resolveCommand(servletRequest);
        AtomicReference<DefectReasonView> result = new AtomicReference<>();
        var execution = commands.execute(
                new CommandRequest(
                        "production.quality.saveDefectReason",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, command)),
                () -> {
                    DefectReasonView view = defectReasons.save(command);
                    result.set(view);
                    return new CommandOutcome("DEFECT_REASON", view.defectReasonCode());
                });
        return commandResult(result.get(), execution.replayed(), HttpStatus.OK);
    }

    @PostMapping("/defect-reasons/{code}/status")
    public ResponseEntity<DefectReasonView> changeDefectReasonStatus(
            @PathVariable String code,
            @RequestBody ChangeDefectReasonStatusRequest request,
            HttpServletRequest servletRequest) {
        RequestContext context = contextResolver.resolveCommand(servletRequest);
        AtomicReference<DefectReasonView> result = new AtomicReference<>();
        var execution = commands.execute(
                new CommandRequest(
                        "production.quality.changeDefectReasonStatus",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(
                                objectMapper,
                                Map.of("defectReasonCode", code, "request", request))),
                () -> {
                    DefectReasonView view = defectReasons.changeStatus(
                            new ChangeDefectReasonStatusCommand(
                                    code, request.status(), request.expectedRowVersion()));
                    result.set(view);
                    return new CommandOutcome("DEFECT_REASON", view.defectReasonCode());
                });
        return commandResult(result.get(), execution.replayed(), HttpStatus.OK);
    }

    @PostMapping("/corrections")
    public ResponseEntity<ProductionCorrectionResult> correct(
            @RequestBody ProductionCorrectionCommand command, HttpServletRequest servletRequest) {
        RequestContext context = contextResolver.resolveCommand(servletRequest);
        AtomicReference<ProductionCorrectionResult> result = new AtomicReference<>();
        var execution = commands.execute(
                new CommandRequest(
                        "production.quality.correct",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, command)),
                () -> {
                    ProductionCorrectionResult correction = corrections.correct(command);
                    result.set(correction);
                    return new CommandOutcome(
                            "PRODUCTION_CORRECTION", correction.correctionCommandBusinessId());
                });
        return commandResult(result.get(), execution.replayed(), HttpStatus.OK);
    }

    @PostMapping("/responsibility-corrections")
    public ResponseEntity<ResponsibilityView> correctResponsibility(
            @RequestBody CorrectResponsibilityCommand command,
            HttpServletRequest servletRequest) {
        RequestContext context = contextResolver.resolveCommand(servletRequest);
        AtomicReference<ResponsibilityView> result = new AtomicReference<>();
        var execution = commands.execute(
                new CommandRequest(
                        "production.quality.correctResponsibility",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, command)),
                () -> {
                    ResponsibilityView view = responsibilities.correct(command);
                    result.set(view);
                    return new CommandOutcome(
                            "PRODUCTION_RESPONSIBILITY", view.responsibilityChainBusinessId());
                });
        return commandResult(result.get(), execution.replayed(), HttpStatus.OK);
    }

    @GetMapping("/dispositions")
    public List<DispositionView> dispositions(
            @RequestParam(required = false) String taskItemBusinessId,
            @RequestParam(required = false) String productionSourceBusinessId) {
        if (taskItemBusinessId != null && !taskItemBusinessId.isBlank()) {
            return dispositionQuery.findByTaskItem(taskItemBusinessId);
        }
        if (productionSourceBusinessId != null && !productionSourceBusinessId.isBlank()) {
            return dispositionQuery.findBySource(productionSourceBusinessId);
        }
        throw new IllegalArgumentException(
                "必须提供 taskItemBusinessId 或 productionSourceBusinessId");
    }

    private static <T> ResponseEntity<T> commandResult(
            T result, boolean replayed, HttpStatus successStatus) {
        if (replayed) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(successStatus).body(result);
    }
}

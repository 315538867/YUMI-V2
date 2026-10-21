package com.yumi.workforce.api.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.api.idempotency.CommandOutcome;
import com.yumi.platform.api.idempotency.CommandRequest;
import com.yumi.platform.api.http.RequestContextResolver;
import com.yumi.platform.api.idempotency.CommandExecutionFacade;
import com.yumi.workforce.api.AssignmentEligibility;
import com.yumi.workforce.api.AssignmentSubjectCurrentView;
import com.yumi.workforce.api.AssignmentSubjectQuery;
import com.yumi.workforce.api.AssignmentSubjectRef;
import com.yumi.workforce.api.AssignmentSubjectType;
import com.yumi.workforce.api.UpdateEfficiencyStandardCommand;
import com.yumi.workforce.api.UpdateWorkTimeStandardCommand;
import com.yumi.workforce.api.WorkforceApplicationService;
import com.yumi.workforce.api.WorkforceStandardQuery;
import com.yumi.workforce.api.WorkforceStandardView;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 人员标准与执行主体 HTTP 适配器（交付清单 W02）。
 *
 * <p>只调用标准命令、标准查询与执行主体查询；标准写入按平台幂等契约执行，资格检查不需要幂等键。
 */
@RestController
@RequestMapping("/api/workforce/standards")
public class WorkforceStandardsController {

    private final WorkforceApplicationService workforce;

    private final WorkforceStandardQuery standards;

    private final AssignmentSubjectQuery subjects;

    private final CommandExecutionFacade commands;

    private final ObjectMapper objectMapper;

    private final RequestContextResolver contextResolver;

    public WorkforceStandardsController(
            WorkforceApplicationService workforce,
            WorkforceStandardQuery standards,
            AssignmentSubjectQuery subjects,
            CommandExecutionFacade commands,
            ObjectMapper objectMapper,
            RequestContextResolver contextResolver) {
        this.workforce = workforce;
        this.standards = standards;
        this.subjects = subjects;
        this.commands = commands;
        this.objectMapper = objectMapper;
        this.contextResolver = contextResolver;
    }

    /** 执行资格检查请求体；主体可整体提交，也可平铺主体类型与业务标识。 */
    public record EligibilityRequest(
            AssignmentSubjectRef subject,
            AssignmentSubjectType subjectType,
            String subjectBusinessId,
            String workTypeCode,
            LocalDate workDate) {
    }

    @GetMapping
    public WorkforceStandardView getStandards() {
        return standards.getStandards();
    }

    @PutMapping("/efficiency")
    public ResponseEntity<WorkforceStandardView> updateEfficiency(
            @RequestBody UpdateEfficiencyStandardCommand request,
            HttpServletRequest servletRequest) {
        var context = contextResolver.resolveCommand(servletRequest);
        commands.execute(
                new CommandRequest(
                        "workforce.updateEfficiencyStandard",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, request)),
                () -> {
                    workforce.updateEfficiencyStandard(request);
                    return new CommandOutcome(
                            "EFFICIENCY_STANDARD", request.standardBusinessId());
                });
        return ResponseEntity.ok(standards.getStandards());
    }

    @PutMapping("/work-time")
    public ResponseEntity<WorkforceStandardView> updateWorkTime(
            @RequestBody UpdateWorkTimeStandardCommand request,
            HttpServletRequest servletRequest) {
        var context = contextResolver.resolveCommand(servletRequest);
        commands.execute(
                new CommandRequest(
                        "workforce.updateWorkTimeStandard",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, request)),
                () -> {
                    workforce.updateWorkTimeStandard(request);
                    return new CommandOutcome(
                            "WORK_TIME_STANDARD", "DAILY_WORK_TIME");
                });
        return ResponseEntity.ok(standards.getStandards());
    }

    @GetMapping("/subjects/{subjectType}/{subjectBusinessId}")
    public AssignmentSubjectCurrentView getSubject(
            @PathVariable AssignmentSubjectType subjectType,
            @PathVariable String subjectBusinessId) {
        return subjects.getCurrent(new AssignmentSubjectRef(subjectType, subjectBusinessId));
    }

    @PostMapping("/eligibility")
    public AssignmentEligibility checkEligibility(@RequestBody EligibilityRequest request) {
        AssignmentSubjectRef subject = request.subject() != null
                ? request.subject()
                : new AssignmentSubjectRef(request.subjectType(), request.subjectBusinessId());
        return subjects.checkEligibility(
                subject, request.workTypeCode(), request.workDate());
    }
}

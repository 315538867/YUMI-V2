package com.yumi.workforce.api.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.api.idempotency.CommandOutcome;
import com.yumi.platform.api.idempotency.CommandRequest;
import com.yumi.platform.api.http.RequestContextResolver;
import com.yumi.platform.api.idempotency.CommandExecutionFacade;
import com.yumi.workforce.api.ChangeQualificationCommand;
import com.yumi.workforce.api.CreateTeamCommand;
import com.yumi.workforce.api.DeactivateTeamCommand;
import com.yumi.workforce.api.EmployeeRef;
import com.yumi.workforce.api.EmploymentStatus;
import com.yumi.workforce.api.EmployeeView;
import com.yumi.workforce.api.HireEmployeeCommand;
import com.yumi.workforce.api.LeaveEmployeeCommand;
import com.yumi.workforce.api.RehireEmployeeCommand;
import com.yumi.workforce.api.TeamRef;
import com.yumi.workforce.api.TeamStatus;
import com.yumi.workforce.api.TeamView;
import com.yumi.workforce.api.UpdateEmployeeCommand;
import com.yumi.workforce.api.UpdateTeamCommand;
import com.yumi.workforce.api.WorkforceApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 人员与小组 HTTP 适配器（交付清单 W01）。
 *
 * <p>只调用人员公开命令；员工与平台账号完全分开，所有写入按平台幂等契约执行。
 */
@RestController
@RequestMapping("/api/workforce")
public class WorkforceController {

    private final WorkforceApplicationService workforce;

    private final CommandExecutionFacade commands;

    private final ObjectMapper objectMapper;

    private final RequestContextResolver contextResolver;

    public WorkforceController(
            WorkforceApplicationService workforce,
            CommandExecutionFacade commands,
            ObjectMapper objectMapper,
            RequestContextResolver contextResolver) {
        this.workforce = workforce;
        this.commands = commands;
        this.objectMapper = objectMapper;
        this.contextResolver = contextResolver;
    }

    /** 修改员工请求体；员工标识来自路径。 */
    public record UpdateEmployeeRequest(String name, String phone, long expectedRowVersion) {
    }

    /** 离职请求体；员工标识来自路径。 */
    public record LeaveEmployeeRequest(
            LocalDate effectiveDate, String reason, long expectedRowVersion) {
    }

    /** 返聘请求体；员工标识来自路径。 */
    public record RehireEmployeeRequest(LocalDate effectiveDate, long expectedRowVersion) {
    }

    /** 工种资格请求体；员工标识来自路径。 */
    public record QualificationRequest(String workTypeCode, LocalDate effectiveDate) {
    }

    /** 修改小组请求体；小组标识来自路径。 */
    public record UpdateTeamRequest(
            String name, Set<String> memberEmployeeBusinessIds, long expectedRowVersion) {
    }

    /** 停用小组请求体；小组标识来自路径。 */
    public record DeactivateTeamRequest(long expectedRowVersion) {
    }

    @PostMapping("/employees")
    public ResponseEntity<EmployeeView> hire(
            @RequestBody HireEmployeeCommand request, HttpServletRequest servletRequest) {
        var context = contextResolver.resolveCommand(servletRequest);
        var execution = commands.execute(
                new CommandRequest(
                        "workforce.hireEmployee",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, request)),
                () -> {
                    EmployeeRef hired = workforce.hire(request);
                    return new CommandOutcome("EMPLOYEE", hired.employeeBusinessId());
                });
        EmployeeView view = findEmployee(execution.outcome().resultBusinessId());
        return ResponseEntity.status(execution.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(view);
    }

    @PutMapping("/employees/{employeeBusinessId}")
    public EmployeeView updateEmployee(
            @PathVariable String employeeBusinessId,
            @RequestBody UpdateEmployeeRequest request,
            HttpServletRequest servletRequest) {
        UpdateEmployeeCommand command = new UpdateEmployeeCommand(
                employeeBusinessId, request.name(), request.phone(), request.expectedRowVersion());
        var context = contextResolver.resolveCommand(servletRequest);
        commands.execute(
                new CommandRequest(
                        "workforce.updateEmployee",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, command)),
                () -> {
                    EmployeeRef updated = workforce.updateEmployee(command);
                    return new CommandOutcome("EMPLOYEE", updated.employeeBusinessId());
                });
        return findEmployee(employeeBusinessId);
    }

    @PostMapping("/employees/{employeeBusinessId}/leave")
    public EmployeeView leave(
            @PathVariable String employeeBusinessId,
            @RequestBody LeaveEmployeeRequest request,
            HttpServletRequest servletRequest) {
        LeaveEmployeeCommand command = new LeaveEmployeeCommand(
                employeeBusinessId, request.effectiveDate(), request.reason(),
                request.expectedRowVersion());
        var context = contextResolver.resolveCommand(servletRequest);
        commands.execute(
                new CommandRequest(
                        "workforce.leaveEmployee",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, command)),
                () -> {
                    EmployeeRef updated = workforce.leave(command);
                    return new CommandOutcome("EMPLOYEE", updated.employeeBusinessId());
                });
        return findEmployee(employeeBusinessId);
    }

    @PostMapping("/employees/{employeeBusinessId}/rehire")
    public EmployeeView rehire(
            @PathVariable String employeeBusinessId,
            @RequestBody RehireEmployeeRequest request,
            HttpServletRequest servletRequest) {
        RehireEmployeeCommand command = new RehireEmployeeCommand(
                employeeBusinessId, request.effectiveDate(), request.expectedRowVersion());
        var context = contextResolver.resolveCommand(servletRequest);
        commands.execute(
                new CommandRequest(
                        "workforce.rehireEmployee",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, command)),
                () -> {
                    EmployeeRef updated = workforce.rehire(command);
                    return new CommandOutcome("EMPLOYEE", updated.employeeBusinessId());
                });
        return findEmployee(employeeBusinessId);
    }

    @PostMapping("/employees/{employeeBusinessId}/qualifications/grant")
    public EmployeeView grantQualification(
            @PathVariable String employeeBusinessId,
            @RequestBody QualificationRequest request,
            HttpServletRequest servletRequest) {
        return changeQualification(
                "workforce.grantQualification", employeeBusinessId, request, servletRequest, true);
    }

    @PostMapping("/employees/{employeeBusinessId}/qualifications/revoke")
    public EmployeeView revokeQualification(
            @PathVariable String employeeBusinessId,
            @RequestBody QualificationRequest request,
            HttpServletRequest servletRequest) {
        return changeQualification(
                "workforce.revokeQualification", employeeBusinessId, request, servletRequest, false);
    }

    @GetMapping("/employees")
    public List<EmployeeView> listEmployees(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) EmploymentStatus status,
            @RequestParam(required = false) String workTypeCode,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        if (page == null && size == null) {
            return workforce.listEmployees();
        }
        return workforce.searchEmployees(keyword, status, workTypeCode,
                page == null ? 1 : page, size == null ? 20 : size);
    }

    @GetMapping("/employees/{employeeBusinessId}")
    public EmployeeView getEmployee(@PathVariable String employeeBusinessId) {
        return workforce.getEmployee(employeeBusinessId);
    }

    @PostMapping("/teams")
    public ResponseEntity<TeamView> createTeam(
            @RequestBody CreateTeamCommand request, HttpServletRequest servletRequest) {
        var context = contextResolver.resolveCommand(servletRequest);
        var execution = commands.execute(
                new CommandRequest(
                        "workforce.createTeam",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, request)),
                () -> {
                    TeamRef created = workforce.createTeam(request);
                    return new CommandOutcome("TEAM", created.teamBusinessId());
                });
        TeamView view = findTeam(execution.outcome().resultBusinessId());
        return ResponseEntity.status(execution.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(view);
    }

    @PutMapping("/teams/{teamBusinessId}")
    public TeamView updateTeam(
            @PathVariable String teamBusinessId,
            @RequestBody UpdateTeamRequest request,
            HttpServletRequest servletRequest) {
        UpdateTeamCommand command = new UpdateTeamCommand(
                teamBusinessId, request.name(), request.memberEmployeeBusinessIds(),
                request.expectedRowVersion());
        var context = contextResolver.resolveCommand(servletRequest);
        commands.execute(
                new CommandRequest(
                        "workforce.updateTeam",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, command)),
                () -> {
                    TeamRef updated = workforce.updateTeam(command);
                    return new CommandOutcome("TEAM", updated.teamBusinessId());
                });
        return findTeam(teamBusinessId);
    }

    @PostMapping("/teams/{teamBusinessId}/deactivate")
    public TeamView deactivateTeam(
            @PathVariable String teamBusinessId,
            @RequestBody DeactivateTeamRequest request,
            HttpServletRequest servletRequest) {
        DeactivateTeamCommand command = new DeactivateTeamCommand(
                teamBusinessId, request.expectedRowVersion());
        var context = contextResolver.resolveCommand(servletRequest);
        commands.execute(
                new CommandRequest(
                        "workforce.deactivateTeam",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, command)),
                () -> {
                    workforce.deactivateTeam(command);
                    return new CommandOutcome("TEAM", teamBusinessId);
                });
        return findTeam(teamBusinessId);
    }

    @GetMapping("/teams")
    public List<TeamView> listTeams(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) TeamStatus status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        if (page == null && size == null) {
            return workforce.listTeams();
        }
        return workforce.searchTeams(keyword, status,
                page == null ? 1 : page, size == null ? 20 : size);
    }

    @GetMapping("/teams/{teamBusinessId}")
    public TeamView getTeam(@PathVariable String teamBusinessId) {
        return workforce.getTeam(teamBusinessId);
    }

    private EmployeeView changeQualification(
            String scope,
            String employeeBusinessId,
            QualificationRequest request,
            HttpServletRequest servletRequest,
            boolean grant) {
        ChangeQualificationCommand command = new ChangeQualificationCommand(
                employeeBusinessId, request.workTypeCode(), request.effectiveDate());
        var context = contextResolver.resolveCommand(servletRequest);
        commands.execute(
                new CommandRequest(
                        scope,
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, command)),
                () -> {
                    if (grant) {
                        workforce.grantWorkType(command);
                    } else {
                        workforce.revokeWorkType(command);
                    }
                    return new CommandOutcome("EMPLOYEE", employeeBusinessId);
                });
        return findEmployee(employeeBusinessId);
    }

    private EmployeeView findEmployee(String employeeBusinessId) {
        return workforce.getEmployee(employeeBusinessId);
    }

    private TeamView findTeam(String teamBusinessId) {
        return workforce.getTeam(teamBusinessId);
    }
}

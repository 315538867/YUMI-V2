package com.yumi.production.api.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.api.http.RequestContext;
import com.yumi.platform.api.idempotency.CommandOutcome;
import com.yumi.platform.api.idempotency.CommandRequest;
import com.yumi.platform.api.http.RequestContextResolver;
import com.yumi.platform.api.idempotency.CommandExecutionFacade;
import com.yumi.production.api.ArrangeTaskCommand;
import com.yumi.production.api.CancelTaskCommand;
import com.yumi.production.api.ProductionProjectionRow;
import com.yumi.production.api.ProductionQuantityLedger;
import com.yumi.production.api.ProductionQuery;
import com.yumi.production.api.TaskApplicationService;
import com.yumi.production.api.TaskRef;
import com.yumi.production.api.TaskView;
import com.yumi.production.api.TaskVerificationService;
import com.yumi.production.api.VerificationView;
import com.yumi.production.api.VerifyOtherTaskCommand;
import com.yumi.production.api.VerifyTaskCommand;
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
 * 生产任务 HTTP 适配器（交付清单 N01）。
 *
 * <p>只调用生产公开命令与查询，承载任务安排、一次最终核验、任务详情及需求缺口投影；
 * 任务列表、调整与取消目前没有公开 API 方法，不在此伪造。
 */
@RestController
@RequestMapping("/api/production/tasks")
public class ProductionTaskController {

    private final TaskApplicationService tasks;

    private final TaskVerificationService verifications;

    private final ProductionQuery production;

    private final CommandExecutionFacade commands;

    private final RequestContextResolver contextResolver;

    private final ObjectMapper objectMapper;

    public ProductionTaskController(
            TaskApplicationService tasks,
            TaskVerificationService verifications,
            ProductionQuery production,
            CommandExecutionFacade commands,
            RequestContextResolver contextResolver,
            ObjectMapper objectMapper) {
        this.tasks = tasks;
        this.verifications = verifications;
        this.production = production;
        this.commands = commands;
        this.contextResolver = contextResolver;
        this.objectMapper = objectMapper;
    }

    /** 任务取消请求体。 */
    public record CancelTaskRequest(String reasonText) {
    }

    @PostMapping("/{taskBusinessId}/cancellation")
    public ResponseEntity<TaskRef> cancel(
            @PathVariable String taskBusinessId,
            @RequestBody CancelTaskRequest request,
            jakarta.servlet.http.HttpServletRequest servletRequest) {
        com.yumi.platform.api.http.RequestContext context =
                contextResolver.resolveCommand(servletRequest);
        AtomicReference<TaskRef> result = new AtomicReference<>();
        var execution = commands.execute(
                new CommandRequest(
                        "production.task.cancel",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(
                                objectMapper,
                                Map.of("taskBusinessId", taskBusinessId,
                                        "reasonText", request.reasonText()))),
                () -> {
                    TaskRef cancelled = tasks.cancel(
                            new CancelTaskCommand(taskBusinessId, request.reasonText()));
                    result.set(cancelled);
                    return new CommandOutcome("TASK", cancelled.taskBusinessId());
                });
        return commandResult(result.get(), execution.replayed(), HttpStatus.OK);
    }

    @GetMapping
    public List<TaskView> listTasks(@RequestParam java.time.LocalDate workDate) {
        return tasks.listTasks(workDate);
    }

    @GetMapping("/{taskBusinessId}")
    public TaskView getTask(@PathVariable String taskBusinessId) {
        return tasks.getTask(taskBusinessId);
    }

    @PostMapping("/arrangements")
    public ResponseEntity<TaskRef> arrange(
            @RequestBody ArrangeTaskCommand command, HttpServletRequest servletRequest) {
        RequestContext context = contextResolver.resolveCommand(servletRequest);
        AtomicReference<TaskRef> result = new AtomicReference<>();
        var execution = commands.execute(
                new CommandRequest(
                        "production.task.arrange",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, command)),
                () -> {
                    TaskRef task = tasks.arrange(command);
                    result.set(task);
                    return new CommandOutcome("TASK", task.taskBusinessId());
                });
        return commandResult(result.get(), execution.replayed(), HttpStatus.CREATED);
    }

    @PostMapping("/verification")
    public ResponseEntity<VerificationView> verify(
            @RequestBody VerifyTaskCommand command, HttpServletRequest servletRequest) {
        RequestContext context = contextResolver.resolveCommand(servletRequest);
        AtomicReference<VerificationView> result = new AtomicReference<>();
        var execution = commands.execute(
                new CommandRequest(
                        "production.task.verify",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, command)),
                () -> {
                    VerificationView verification = verifications.verify(command);
                    result.set(verification);
                    return new CommandOutcome("TASK", verification.taskBusinessId());
                });
        return commandResult(result.get(), execution.replayed(), HttpStatus.OK);
    }

    @PostMapping("/other-verification")
    public ResponseEntity<VerificationView> verifyOther(
            @RequestBody VerifyOtherTaskCommand command, HttpServletRequest servletRequest) {
        RequestContext context = contextResolver.resolveCommand(servletRequest);
        AtomicReference<VerificationView> result = new AtomicReference<>();
        var execution = commands.execute(
                new CommandRequest(
                        "production.task.verifyOther",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, command)),
                () -> {
                    VerificationView verification = verifications.verifyOtherTask(command);
                    result.set(verification);
                    return new CommandOutcome("TASK", verification.taskBusinessId());
                });
        return commandResult(result.get(), execution.replayed(), HttpStatus.OK);
    }

    @GetMapping("/demands")
    public List<ProductionProjectionRow> demands(
            @RequestParam String productBusinessId,
            @RequestParam(defaultValue = "50") int limit) {
        return production.projectionsOfProduct(productBusinessId, limit);
    }

    @GetMapping("/demands/{demandBusinessId}")
    public ProductionQuantityLedger demand(@PathVariable String demandBusinessId) {
        return production.ledgerOfDemand(demandBusinessId);
    }

    private static <T> ResponseEntity<T> commandResult(
            T result, boolean replayed, HttpStatus successStatus) {
        if (replayed) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(successStatus).body(result);
    }
}

package com.yumi.platform.aftercommit.api.http;

import com.yumi.platform.aftercommit.application.AfterCommitTaskService;
import com.yumi.platform.aftercommit.api.AfterCommitTaskStatus;
import com.yumi.platform.aftercommit.api.AfterCommitTaskView;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * 提交后任务监控与恢复 HTTP 适配器（交付清单 P03）。
 *
 * <p>需要 {@code platform.task.manage}；重试沿用原任务身份与去重键，放弃只标记任务终止，
 * 不修改原业务事实。非管理员直接调用同样被拒绝。
 */
@RestController
@RequestMapping("/api/platform/tasks")
public class AfterCommitTaskController {

    private final AfterCommitTaskService tasks;

    private final AuthorizationService authorization;

    public AfterCommitTaskController(AfterCommitTaskService tasks, AuthorizationService authorization) {
        this.tasks = tasks;
        this.authorization = authorization;
    }

    /** 任务响应：包含失败原因、重试次数与来源载荷。 */
    public record TaskResponse(
            String taskBusinessId,
            String taskType,
            String deduplicationKey,
            String status,
            int attempts,
            int maxAttempts,
            Instant nextRunAt,
            Instant leaseUntil,
            String leaseOwner,
            String lastError,
            Map<String, String> payload,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** 放弃任务请求体；必须填写原因。 */
    public record AbandonRequest(String reason) {
    }

    @GetMapping
    public List<TaskResponse> list(@RequestParam String status, @RequestParam(defaultValue = "50") int limit) {
        requireTaskManage();
        return tasks.listByStatus(AfterCommitTaskStatus.valueOf(status), limit).stream()
                .map(AfterCommitTaskController::toResponse)
                .toList();
    }

    @GetMapping("/{taskBusinessId}")
    public TaskResponse get(@PathVariable String taskBusinessId) {
        requireTaskManage();
        return toResponse(tasks.view(taskBusinessId));
    }

    @PostMapping("/{taskBusinessId}/retry")
    public ResponseEntity<Map<String, String>> retry(@PathVariable String taskBusinessId) {
        requireTaskManage();
        tasks.retry(taskBusinessId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("taskBusinessId", taskBusinessId, "status", "PENDING"));
    }

    @PostMapping("/{taskBusinessId}/abandon")
    public TaskResponse abandon(
            @PathVariable String taskBusinessId, @RequestBody AbandonRequest request) {
        requireTaskManage();
        tasks.abandon(taskBusinessId, request.reason());
        return toResponse(tasks.view(taskBusinessId));
    }

    private void requireTaskManage() {
        try {
            authorization.require(PermissionCodes.PLATFORM_TASK_MANAGE, ResourceScope.GLOBAL);
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ApiException(
                    ApiErrorType.FORBIDDEN, "PERMISSION_DENIED", "当前账号没有管理后台任务的权限");
        }
    }

    private static TaskResponse toResponse(AfterCommitTaskView view) {
        return new TaskResponse(
                view.taskBusinessId(),
                view.taskType(),
                view.deduplicationKey(),
                view.status().name(),
                view.attempts(),
                view.maxAttempts(),
                view.nextRunAt(),
                view.leaseUntil(),
                view.leaseOwner(),
                view.lastError(),
                view.payload(),
                view.createdAt(),
                view.updatedAt());
    }
}

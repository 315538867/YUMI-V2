package com.yumi.production.overtime;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 超额任务 API（任务 5.14）：创建、查询与一次性核验。
 *
 * 独立于普通生产任务：超额预占不进入 `production_tasks.task_type`，不产生流入与履约事实。
 * 写命令的 `Idempotency-Key` 由 `IdempotencyFilter` 统一门禁。
 */
@RestController
@RequestMapping("/api/overtime-tasks")
public class OvertimeTaskController {

    private final OvertimeTaskService service;

    public OvertimeTaskController(OvertimeTaskService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OvertimeTaskViews.OvertimeTaskView create(
            @RequestBody OvertimeTaskViews.CreateOvertimeTaskRequest request) {
        return service.create(request);
    }

    /** 按执行日期列出超额任务（工作台提醒读取）。 */
    @GetMapping
    public java.util.List<OvertimeTaskViews.OvertimeTaskView> list(
            @org.springframework.web.bind.annotation.RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            java.time.LocalDate taskDate) {
        return service.list(taskDate);
    }

    @GetMapping("/{id}")
    public OvertimeTaskViews.OvertimeTaskView get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping("/{id}/verify")
    public OvertimeTaskViews.OvertimeTaskView verify(
            @PathVariable long id, @RequestBody OvertimeTaskViews.VerifyOvertimeTaskRequest request) {
        return service.verify(id, request);
    }
}

package com.yumi.production.task;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 生产任务 API（阶段五 5.2/5.5）：多订单多产品任务头 + 明细的创建与查询，以及待执行明细取消。
 * 核验、返工来源、报废回转、超额与其他排班在各自资源下暴露，不从本控制器绕过来源校验。
 */
@RestController
@RequestMapping("/api/production-tasks")
public class ProductionTaskController {

    private final ProductionTaskService service;

    public ProductionTaskController(ProductionTaskService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProductionTaskViews.TaskView> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) Long workTypeId,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) Long productId) {
        return service.list(dateFrom, dateTo, employeeId, workTypeId, taskType, status, orderId, productId);
    }

    @GetMapping("/{id}")
    public ProductionTaskViews.TaskView get(@PathVariable long id) {
        return service.get(id);
    }

    /** 任务事实时间线（阶段五 5.18）：按 `factTime ASC, factType ASC, factId ASC` 返回不可变事实。 */
    @GetMapping("/{id}/facts")
    public List<ProductionTaskViews.FactView> facts(@PathVariable long id) {
        return service.facts(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductionTaskViews.TaskView create(@RequestBody ProductionTaskService.CreateRequest request) {
        return service.create(request);
    }

    /** 取消单条待执行明细（阶段五 5.13）：只允许 PENDING 明细，原因必填。 */
    @PostMapping("/{taskId}/items/{itemId}/cancel")
    public ProductionTaskViews.TaskView cancelItem(@PathVariable long taskId, @PathVariable long itemId,
                                                   @RequestBody CancelItemRequest request) {
        return service.cancelItem(taskId, itemId, request.reason());
    }

    /** 明细取消入参：原因必填。 */
    public record CancelItemRequest(String reason) {
    }
}

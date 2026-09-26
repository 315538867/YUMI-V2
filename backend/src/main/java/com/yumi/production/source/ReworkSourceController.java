package com.yumi.production.source;

import com.yumi.production.task.ProductionTaskViews;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 返工来源 API（阶段五 5.9–5.11）：核验不隐式创建来源，管理员必须基于返工事实显式创建后再安排返工任务。
 */
@RestController
@RequestMapping("/api/rework-sources")
public class ReworkSourceController {

    private final ReworkSourceService service;

    public ReworkSourceController(ReworkSourceService service) {
        this.service = service;
    }

    @GetMapping
    public List<ReworkSourceViews.ReworkSourceView> list(
            @RequestParam(required = false) Long orderItemId,
            @RequestParam(required = false) String node,
            @RequestParam(required = false) String status) {
        return service.list(orderItemId, node, status);
    }

    @GetMapping("/{id}")
    public ReworkSourceViews.ReworkSourceView get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReworkSourceViews.ReworkSourceView create(
            @RequestBody ReworkSourceService.CreateRequest request) {
        return service.create(request);
    }

    /** 从来源余额创建 REWORK 任务明细：工序强制等于来源发生工序。 */
    @PostMapping("/{id}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductionTaskViews.TaskView createTask(@PathVariable long id,
                                                   @RequestBody ReworkSourceService.CreateTaskRequest request) {
        return service.createTask(id, request);
    }
}

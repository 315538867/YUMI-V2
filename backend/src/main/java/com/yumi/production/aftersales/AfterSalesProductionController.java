package com.yumi.production.aftersales;

import com.yumi.production.task.ProductionTaskViews;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 售后生产来源 API（阶段八 8.4/8.5）：查询售后来源与从售后来源创建生产任务。
 *
 * <p>售后生产只能使用售后专用来源，不得通过普通 `/api/production-tasks` 请求绕过售后来源校验。
 */
@RestController
@RequestMapping("/api/after-sales/{caseId}/production-sources")
public class AfterSalesProductionController {

    private final AfterSalesProductionService service;

    public AfterSalesProductionController(AfterSalesProductionService service) {
        this.service = service;
    }

    @GetMapping
    public List<AfterSalesProductionViews.AfterSalesSourceView> list(@PathVariable long caseId) {
        return service.listSources(caseId);
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductionTaskViews.TaskView createTask(
            @PathVariable long caseId,
            @RequestBody AfterSalesProductionViews.CreateAfterSalesTaskRequest request) {
        return service.createTask(caseId, request);
    }
}

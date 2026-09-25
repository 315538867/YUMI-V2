package com.yumi.production.source;

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

/** 报废重做来源 API（任务 5.7）：来源查询/创建与从来源创建计划。 */
@RestController
@RequestMapping("/api/remake-sources")
public class RemakeSourceController {

    private final RemakeSourceService service;

    public RemakeSourceController(RemakeSourceService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProductionSourceViews.RemakeSourceView> list(@RequestParam Long orderItemId) {
        return service.list(orderItemId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductionSourceViews.RemakeSourceView create(
            @RequestBody ProductionSourceViews.CreateRemakeSourceRequest request) {
        return service.create(request);
    }

    @PostMapping("/{id}/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public com.yumi.production.plan.ProductionPlanViews.PlanView createPlan(
            @PathVariable long id, @RequestBody ProductionSourceViews.CreateSourcePlanRequest request) {
        return service.createPlan(id, request);
    }
}

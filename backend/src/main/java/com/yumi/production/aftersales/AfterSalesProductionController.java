package com.yumi.production.aftersales;

import com.yumi.production.plan.ProductionPlanViews;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 售后生产来源 API（任务 8.4/8.5）：售后来源查询与从售后来源创建生产计划。 */
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

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductionPlanViews.PlanView createPlan(
            @PathVariable long caseId,
            @RequestBody AfterSalesProductionViews.CreateAfterSalesPlanRequest request) {
        return service.createPlan(caseId, request);
    }
}

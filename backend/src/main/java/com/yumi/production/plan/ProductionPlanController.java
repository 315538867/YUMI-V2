package com.yumi.production.plan;

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
 * 生产计划 API（任务 5.2/5.3）：直接返回业务 DTO，由统一信封包装；错误路径抛 ApiException 走统一错误契约。
 * 核验、取消、返工/重做来源、超额与其他排班在后续任务追加。
 */
@RestController
@RequestMapping("/api/production-plans")
public class ProductionPlanController {

    private final ProductionPlanService service;
    private final ProductionPlanCancellationService cancellationService;

    public ProductionPlanController(ProductionPlanService service,
                                    ProductionPlanCancellationService cancellationService) {
        this.service = service;
        this.cancellationService = cancellationService;
    }

    @GetMapping
    public List<ProductionPlanViews.PlanView> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) String node,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) Long orderItemId) {
        return service.list(dateFrom, dateTo, employeeId, node, status, orderId, orderItemId);
    }

    @GetMapping("/{id}")
    public ProductionPlanViews.PlanView get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductionPlanViews.PlanView create(@RequestBody CreateProductionPlanRequest request) {
        return service.create(request);
    }

    /** 取消待执行计划（任务 5.8）：原因必填，返工/重做计划取消后恢复来源余额。 */
    @PostMapping("/{id}/cancel")
    public ProductionPlanViews.PlanView cancel(@PathVariable long id, @RequestBody CancelPlanRequest request) {
        return cancellationService.cancel(id, request.reason());
    }

    /** 取消入参：原因必填。 */
    public record CancelPlanRequest(String reason) {
    }
}

package com.yumi.production.verification;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 生产核验 API（任务 5.4）：计划的一次性核验，路径挂在计划资源下。 */
@RestController
@RequestMapping("/api/production-plans")
public class ProductionVerificationController {

    private final ProductionVerificationService service;

    public ProductionVerificationController(ProductionVerificationService service) {
        this.service = service;
    }

    @PostMapping("/{id}/verify")
    public ProductionVerificationViews.VerificationView verify(@PathVariable long id,
                                                               @RequestBody VerifyProductionPlanRequest request) {
        return service.verify(id, request);
    }
}

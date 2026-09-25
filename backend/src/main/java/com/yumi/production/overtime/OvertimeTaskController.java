package com.yumi.production.overtime;

import com.yumi.production.verification.ProductionVerificationService;
import com.yumi.production.verification.ProductionVerificationViews;
import com.yumi.production.verification.VerifyProductionPlanRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 超额任务 API（任务 5.10/5.11）：创建与核验。
 * 核验复用生产核验服务（同一等式、可执行上限与分流口径），并额外释放预占、生成计划待调整提醒。
 */
@RestController
@RequestMapping("/api/overtime-tasks")
public class OvertimeTaskController {

    private final OvertimeTaskService service;
    private final ProductionVerificationService verificationService;

    public OvertimeTaskController(OvertimeTaskService service,
                                  ProductionVerificationService verificationService) {
        this.service = service;
        this.verificationService = verificationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OvertimeTaskViews.OvertimeTaskView create(
            @RequestBody OvertimeTaskViews.CreateOvertimeTaskRequest request) {
        return service.create(request);
    }

    @PostMapping("/{id}/verify")
    public ProductionVerificationViews.VerificationView verify(@PathVariable long id,
                                                               @RequestBody VerifyProductionPlanRequest request) {
        return verificationService.verify(id, request);
    }
}

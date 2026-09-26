package com.yumi.production.verification;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 生产核验 API（阶段五 5.7）：一次提交任务内多条明细，逐明细校验、整批原子提交。
 * 前端不得逐条发送多个写请求；失败时按明细与字段定位，整批不产生任何事实。
 */
@RestController
@RequestMapping("/api/production-tasks/{taskId}/verify")
public class ProductionVerificationController {

    private final ProductionVerificationService service;

    public ProductionVerificationController(ProductionVerificationService service) {
        this.service = service;
    }

    @PostMapping
    public ProductionVerificationViews.VerificationView verify(@PathVariable long taskId,
                                                              @RequestBody ProductionVerificationService.VerifyRequest request) {
        return service.verify(taskId, request);
    }
}

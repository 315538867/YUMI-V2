package com.yumi.production.reminder;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工作台提醒 API（阶段五 5.13）：未完成待处理列表与「暂不安排」处理。
 * 重新安排仍走 `POST /api/production-tasks` 创建新的 NORMAL 明细，不修改原任务明细。
 */
@RestController
@RequestMapping("/api/production-reminders")
public class ProductionReminderController {

    private final ProductionReminderService service;

    public ProductionReminderController(ProductionReminderService service) {
        this.service = service;
    }

    @GetMapping("/incomplete")
    public List<ProductionReminderService.ReminderView> listIncomplete(
            @RequestParam(required = false) Long orderItemId,
            @RequestParam(required = false) String node) {
        return service.listIncomplete(orderItemId, node);
    }

    @PostMapping("/{id}/defer")
    public ProductionReminderService.ReminderView defer(@PathVariable long id,
                                                        @RequestBody DeferRequest request) {
        return service.defer(id, request.reason());
    }

    /** 暂不安排入参：原因必填。 */
    public record DeferRequest(String reason) {
    }
}

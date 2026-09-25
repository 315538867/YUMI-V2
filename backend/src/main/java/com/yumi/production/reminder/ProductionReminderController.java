package com.yumi.production.reminder;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 生产提醒 API（任务 5.9/5.12）：未完成待处理与超额提醒的查询与人工处理。 */
@RestController
@RequestMapping("/api/production-reminders")
public class ProductionReminderController {

    private final ProductionReminderService service;

    public ProductionReminderController(ProductionReminderService service) {
        this.service = service;
    }

    @GetMapping("/incomplete")
    public List<ProductionReminderViews.IncompleteReminderView> listIncomplete() {
        return service.listIncomplete();
    }

    @PostMapping("/incomplete/{id}/reschedule")
    public ProductionReminderViews.IncompleteReminderView reschedule(
            @PathVariable long id, @RequestBody ProductionReminderViews.RescheduleRequest request) {
        return service.reschedule(id, request);
    }

    @PostMapping("/incomplete/{id}/defer")
    public ProductionReminderViews.IncompleteReminderView defer(
            @PathVariable long id, @RequestBody ProductionReminderViews.DeferRequest request) {
        return service.defer(id, request);
    }

    @GetMapping("/overtime")
    public List<ProductionReminderViews.OvertimeReminderView> listOvertime() {
        return service.listOvertime();
    }

    @PostMapping("/overtime/{id}/adjust-plan")
    public ProductionReminderViews.OvertimeReminderView adjustPlan(
            @PathVariable long id, @RequestBody ProductionReminderViews.AdjustPlanRequest request) {
        return service.adjustPlan(id, request);
    }

    @PostMapping("/overtime/{id}/no-adjustment")
    public ProductionReminderViews.OvertimeReminderView noAdjustment(
            @PathVariable long id, @RequestBody ProductionReminderViews.NoAdjustmentRequest request) {
        return service.noAdjustment(id, request);
    }
}

package com.yumi.production.otherschedule;

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

/** 其他排班 API（任务 5.13）：创建、查询、一次性工时核验、取消与工时更正。 */
@RestController
@RequestMapping("/api/other-schedules")
public class OtherScheduleController {

    private final OtherScheduleService service;

    public OtherScheduleController(OtherScheduleService service) {
        this.service = service;
    }

    @GetMapping
    public List<OtherScheduleViews.OtherScheduleView> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) String status) {
        return service.list(dateFrom, dateTo, employeeId, status);
    }

    @GetMapping("/{id}")
    public OtherScheduleViews.OtherScheduleView get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OtherScheduleViews.OtherScheduleView create(
            @RequestBody OtherScheduleViews.CreateOtherScheduleRequest request) {
        return service.create(request);
    }

    @PostMapping("/{id}/verify")
    public OtherScheduleViews.OtherScheduleView verify(
            @PathVariable long id, @RequestBody OtherScheduleViews.VerifyOtherScheduleRequest request) {
        return service.verify(id, request);
    }

    @PostMapping("/{id}/cancel")
    public OtherScheduleViews.OtherScheduleView cancel(
            @PathVariable long id, @RequestBody OtherScheduleViews.CancelOtherScheduleRequest request) {
        return service.cancel(id, request);
    }

    @PostMapping("/{id}/corrections")
    public OtherScheduleViews.OtherScheduleView correct(
            @PathVariable long id, @RequestBody OtherScheduleViews.CorrectOtherScheduleRequest request) {
        return service.correct(id, request);
    }
}

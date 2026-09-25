package com.yumi.reports;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 报表 API（任务 9.1/9.2/9.4）：只读，筛选与分页由服务端执行。
 *
 * 例外说明：`/export` 返回 `text/csv` 附件，**不套统一信封**（与 `204` 同类的导出例外）——
 * 导出内容是文件而非业务负载；错误路径仍走统一错误契约（信封）。
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    @GetMapping("/{type}")
    public ReportViews.ReportPage query(
            @PathVariable String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return service.query(type, dateFrom, dateTo, orderId, page, size);
    }

    @GetMapping("/{type}/export")
    public ResponseEntity<String> export(
            @PathVariable String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) Long orderId) {
        var csv = service.exportCsv(type, dateFrom, dateTo, orderId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + type + ".csv\"")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(csv);
    }

    /** 事实重建与投影一致性检查（任务 9.4）：只产出失败证据，不做静默覆盖。 */
    @GetMapping("/consistency")
    public ReportViews.ConsistencyReport consistency() {
        return service.consistency();
    }
}

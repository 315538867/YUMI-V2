package com.yumi.reporting.api.http;

import com.yumi.reporting.api.IngestReportingSourcesCommand;
import com.yumi.reporting.api.RebuildReportingProjectionCommand;
import com.yumi.reporting.api.ReportingRebuildService;
import com.yumi.reporting.api.ReportingIngestionRef;
import com.yumi.reporting.api.ReportingIngestionService;
import com.yumi.reporting.api.ReportingRebuildRef;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报表管理适配器（交付清单 R02）。
 *
 * <p>归集与重建由 ReportingIngestionService / ReportingRebuildService 内部校验
 * {@code reporting.admin}；普通读取权限不能触发。失败保留旧投影。
 */
@RestController
@RequestMapping("/api/reporting/administration")
public class ReportingAdministrationController {

    private final ReportingIngestionService ingestion;
    private final ReportingRebuildService rebuilds;

    public ReportingAdministrationController(
            ReportingIngestionService ingestion, ReportingRebuildService rebuilds) {
        this.ingestion = ingestion;
        this.rebuilds = rebuilds;
    }

    @PostMapping("/ingestions")
    public ResponseEntity<ReportingIngestionRef> ingest(
            @RequestHeader(com.yumi.platform.api.http.HttpHeaders.IDEMPOTENCY_KEY)
                    String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ingestion.ingest(
                new IngestReportingSourcesCommand(idempotencyKey)));
    }

    @PostMapping("/rebuilds")
    public ResponseEntity<ReportingRebuildRef> rebuild(
            @RequestHeader(com.yumi.platform.api.http.HttpHeaders.IDEMPOTENCY_KEY)
                    String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED).body(rebuilds.rebuild(
                new RebuildReportingProjectionCommand(idempotencyKey)));
    }

    @GetMapping("/versions")
    public List<ReportingRebuildService.VersionView> versions() {
        return rebuilds.listVersions();
    }

    @GetMapping("/versions/{versionBusinessId}/differences")
    public List<ReportingRebuildService.DifferenceView> differences(
            @PathVariable String versionBusinessId) {
        return rebuilds.listDifferences(versionBusinessId);
    }
}

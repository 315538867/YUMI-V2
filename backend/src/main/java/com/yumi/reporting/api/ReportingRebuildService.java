package com.yumi.reporting.api;

import java.time.Instant;
import java.util.List;

/** 报表重建与对账管理。 */
public interface ReportingRebuildService {

    ReportingRebuildRef rebuild(RebuildReportingProjectionCommand command);

    List<VersionView> listVersions();

    List<DifferenceView> listDifferences(String versionBusinessId);

    record VersionView(
            String versionBusinessId, String status, long recordCount, int differenceCount,
            Instant builtAt, Instant activatedAt) {
    }

    record DifferenceView(
            String differenceBusinessId, String feedCode, String detailText) {
    }
}

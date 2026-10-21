package com.yumi.reporting.api;

/** 报表来源归集：每个 Feed 独立水位，失败不影响业务，也阻止该 Feed 越过失败项。 */
public interface ReportingIngestionService {

    ReportingIngestionRef ingest(IngestReportingSourcesCommand command);
}

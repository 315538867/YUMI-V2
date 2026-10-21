package com.yumi.reporting.api;

/** 报表归集结果。 */
public record ReportingIngestionRef(int recordCount, int failureCount) {
}

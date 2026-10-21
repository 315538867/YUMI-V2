package com.yumi.reporting.api;

/** 重建结果；差异大于零时保留原可用版本。 */
public record ReportingRebuildRef(
        String versionBusinessId, long recordCount, int differenceCount, boolean activated) {
}

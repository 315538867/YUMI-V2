package com.yumi.production.api;

/** 缺陷原因视图。 */
public record DefectReasonView(
        String defectReasonCode,
        String name,
        DefectReasonStatus status,
        boolean other,
        long rowVersion) {
}

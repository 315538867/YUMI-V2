package com.yumi.production.api;

/** 启用或停用缺陷原因。 */
public record ChangeDefectReasonStatusCommand(
        String defectReasonCode,
        DefectReasonStatus status,
        long expectedRowVersion) {

    public ChangeDefectReasonStatusCommand {
        if (defectReasonCode == null || defectReasonCode.isBlank()) {
            throw new IllegalArgumentException("缺陷原因标识不能为空");
        }
        if (status == null) {
            throw new IllegalArgumentException("目标状态不能为空");
        }
    }
}

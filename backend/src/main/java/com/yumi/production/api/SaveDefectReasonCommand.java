package com.yumi.production.api;

/**
 * 新增或重命名缺陷原因。
 *
 * @param defectReasonCode 稳定标识；新增时给出，修改时必填
 * @param other 是否为“其他”原因（必须填写说明）
 */
public record SaveDefectReasonCommand(
        String defectReasonCode,
        String name,
        boolean other,
        long expectedRowVersion) {

    public SaveDefectReasonCommand {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("缺陷原因名称不能为空");
        }
        if (name.strip().length() > 64) {
            throw new IllegalArgumentException("缺陷原因名称长度不能超过 64");
        }
    }
}

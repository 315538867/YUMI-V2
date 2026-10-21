package com.yumi.production.api;

/**
 * 责任更正：只改责任主体或免除结论，不改数量、处置、业务日期与去向。
 *
 * <p>更正发布 REVERSE 旧链 + APPLY 新链；物理退出与数量投影不变。
 */
public record CorrectResponsibilityCommand(
        String dispositionFactBusinessId,
        ResponsibilityType responsibilityType,
        String responsibleSubjectBusinessId,
        String waivedReason,
        String reasonText,
        long expectedRowVersion) {

    public CorrectResponsibilityCommand {
        if (dispositionFactBusinessId == null || dispositionFactBusinessId.isBlank()) {
            throw new IllegalArgumentException("报废处置标识不能为空");
        }
        if (responsibilityType == null) {
            throw new IllegalArgumentException("责任主体类型不能为空");
        }
        if (responsibilityType == ResponsibilityType.WAIVED) {
            if (waivedReason == null || waivedReason.isBlank()) {
                throw new IllegalArgumentException("责任免除必须填写原因");
            }
        } else if (responsibleSubjectBusinessId == null || responsibleSubjectBusinessId.isBlank()) {
            throw new IllegalArgumentException("责任主体必须填写稳定标识");
        }
    }
}

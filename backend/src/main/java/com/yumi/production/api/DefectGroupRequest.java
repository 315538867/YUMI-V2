package com.yumi.production.api;

import java.util.List;

/**
 * 一条缺陷事实：一个标准缺陷原因及其数量，以及该数量如何处置。
 *
 * <p>规则来源：`docs/一期/11-生产作业模块.md` §7.4、§7.6：
 * <ul>
 *   <li>每条缺陷事实只保存一个原因；选择“其他”必须填写说明；</li>
 *   <li>{@code reworkQuantity + Σ scrapParts.quantity} 必须严格等于 {@code quantity}，
 *       同一件产品不能同时返工与报废；</li>
 *   <li>返工不产生扣款责任；每条报废明细必须给出责任或明确免除结论。</li>
 * </ul>
 */
public record DefectGroupRequest(
        String defectReasonCode,
        String defectReasonText,
        int quantity,
        int reworkQuantity,
        List<ScrapPartRequest> scrapParts) {

    public DefectGroupRequest {
        scrapParts = List.copyOf(scrapParts == null ? List.of() : scrapParts);
        if (defectReasonCode == null || defectReasonCode.isBlank()) {
            throw new IllegalArgumentException("缺陷原因不能为空");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("缺陷数量必须大于 0");
        }
        if (reworkQuantity < 0) {
            throw new IllegalArgumentException("返工数量不能为负");
        }
        int scrapTotal = scrapParts.stream().mapToInt(ScrapPartRequest::quantity).sum();
        if (reworkQuantity + scrapTotal != quantity) {
            throw new IllegalArgumentException(
                    "缺陷处置数量必须等于缺陷数量：返工 " + reworkQuantity + " + 报废 " + scrapTotal
                            + " ≠ " + quantity);
        }
    }

    /** 本条缺陷的报废数量。 */
    public int scrappedQuantity() {
        return scrapParts.stream().mapToInt(ScrapPartRequest::quantity).sum();
    }

    /** 报废明细：数量、实物去向与责任结论。 */
    public record ScrapPartRequest(
            int quantity,
            PhysicalDestination physicalDestination,
            ResponsibilityType responsibilityType,
            String responsibleSubjectBusinessId,
            String waivedReason) {

        public ScrapPartRequest {
            if (quantity <= 0) {
                throw new IllegalArgumentException("报废数量必须大于 0");
            }
            if (physicalDestination == null || physicalDestination == PhysicalDestination.IN_SYSTEM) {
                throw new IllegalArgumentException("报废必须明确实际销毁或转线下去向");
            }
            if (responsibilityType == null) {
                throw new IllegalArgumentException("报废必须明确责任主体或免除结论");
            }
            if (responsibilityType == ResponsibilityType.WAIVED) {
                if (waivedReason == null || waivedReason.isBlank()) {
                    throw new IllegalArgumentException("责任免除必须填写原因");
                }
            } else if (responsibleSubjectBusinessId == null
                    || responsibleSubjectBusinessId.isBlank()) {
                throw new IllegalArgumentException("责任主体必须填写稳定标识");
            }
        }
    }
}

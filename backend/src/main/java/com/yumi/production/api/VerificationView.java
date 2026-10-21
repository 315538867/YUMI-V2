package com.yumi.production.api;

import java.time.LocalDate;
import java.util.List;

/** 核验结果视图：明细数量方程与产生的追溯事实。 */
public record VerificationView(
        String taskBusinessId,
        TaskStatus taskStatus,
        List<ItemVerificationView> items,
        OtherTaskVerificationView otherTask) {

    public VerificationView {
        items = List.copyOf(items);
    }

    /** 明细核验结果。 */
    public record ItemVerificationView(
            String taskItemBusinessId,
            String demandBusinessId,
            String productionSourceBusinessId,
            int plannedQuantity,
            int unfinishedQuantity,
            int completedQuantity,
            int qualifiedQuantity,
            int reworkQuantity,
            int scrappedQuantity,
            int destroyedQuantity,
            int scrapOfflineQuantity,
            List<DefectView> defects,
            List<String> repairSourceBusinessIds) {

        public ItemVerificationView {
            defects = List.copyOf(defects);
            repairSourceBusinessIds = List.copyOf(repairSourceBusinessIds);
        }
    }

    /** 缺陷事实视图。 */
    public record DefectView(
            String defectFactBusinessId, String defectReasonCode, int quantity,
            int reworkQuantity, int scrappedQuantity, List<ScrapView> scrapParts) {

        public DefectView {
            scrapParts = List.copyOf(scrapParts);
        }
    }

    /** 报废明细视图。 */
    public record ScrapView(
            int quantity,
            PhysicalDestination physicalDestination,
            String terminalExitBusinessId,
            ResponsibilityType responsibilityType,
            String responsibleSubjectBusinessId,
            String waivedReason) {
    }

    /** 其他任务核验视图（不产生制品数量、阶段或材料成本来源）。 */
    public record OtherTaskVerificationView(
            String factBusinessId,
            LocalDate workDate,
            Integer plannedQuantity,
            Integer completedQuantity,
            Integer unfinishedQuantity,
            String resultNote,
            String laborSourceBusinessId) {
    }
}

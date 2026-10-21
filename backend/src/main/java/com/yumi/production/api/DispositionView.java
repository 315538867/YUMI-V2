package com.yumi.production.api;

import java.util.List;

/**
 * 质量处置追溯视图：缺陷、处置、终态退出三者各自独立，且每条报废只对应一次退出。
 */
public record DispositionView(
        String taskItemBusinessId,
        String demandBusinessId,
        String productionSourceBusinessId,
        List<DefectTrace> defects) {

    public DispositionView {
        defects = List.copyOf(defects);
    }

    /** 单条缺陷的处置与责任。 */
    public record DefectTrace(
            String defectFactBusinessId,
            String defectReasonCode,
            String defectReasonName,
            int quantity,
            int reworkQuantity,
            int scrappedQuantity,
            List<DispositionTrace> dispositions) {

        public DefectTrace {
            dispositions = List.copyOf(dispositions);
        }
    }

    /** 单条处置：返工只关联返工来源，报废必须带唯一终态退出与责任。 */
    public record DispositionTrace(
            String dispositionFactBusinessId,
            DefectDispositionType dispositionType,
            PhysicalDestination physicalDestination,
            int quantity,
            String terminalExitBusinessId,
            String repairSourceBusinessId,
            ResponsibilityType responsibilityType,
            String responsibleSubjectBusinessId,
            String waivedReason) {
    }
}

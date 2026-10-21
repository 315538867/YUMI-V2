package com.yumi.production.api;

import java.util.List;

/**
 * 一条任务明细的最终核验结果。
 *
 * <p>必须同时满足（基线 11 §7.4）：
 * {@code 计划 = 未完成 + 完成}；{@code 完成 = 合格 + 返工/修补 + 线上报废}；
 * 缺陷事实数量合计 = 返工/修补 + 线上报废。
 *
 * @param taskItemBusinessId 任务明细标识；服务端据此取得已冻结的需求与来源
 * @param unfinishedQuantity 未完成数量：释放占用回到来源可安排数量，不属于不合格
 * @param qualifiedQuantity 合格数量
 * @param reworkQuantity 返工/修补数量合计（须与缺陷分组一致）
 * @param scrappedQuantity 线上报废数量合计（须与缺陷分组一致）
 * @param defects 按缺陷原因分组的缺陷事实
 */
public record TaskItemResultRequest(
        String taskItemBusinessId,
        int unfinishedQuantity,
        int qualifiedQuantity,
        int reworkQuantity,
        int scrappedQuantity,
        List<DefectGroupRequest> defects,
        String resultNote) {

    public TaskItemResultRequest {
        defects = List.copyOf(defects == null ? List.of() : defects);
        if (taskItemBusinessId == null || taskItemBusinessId.isBlank()) {
            throw new IllegalArgumentException("任务明细标识不能为空");
        }
        if (unfinishedQuantity < 0 || qualifiedQuantity < 0 || reworkQuantity < 0 || scrappedQuantity < 0) {
            throw new IllegalArgumentException("核验数量不能为负");
        }
    }

    /** 完成数量 = 合格 + 返工 + 报废。 */
    public int completedQuantity() {
        return qualifiedQuantity + reworkQuantity + scrappedQuantity;
    }

    /** 该明细实际销毁数量。 */
    public int destroyedQuantity() {
        return defects.stream()
                .flatMap(defect -> defect.scrapParts().stream())
                .filter(part -> part.physicalDestination() == PhysicalDestination.DESTROYED)
                .mapToInt(DefectGroupRequest.ScrapPartRequest::quantity)
                .sum();
    }

    /** 该明细转线下库存数量。 */
    public int scrapOfflineQuantity() {
        return defects.stream()
                .flatMap(defect -> defect.scrapParts().stream())
                .filter(part -> part.physicalDestination() == PhysicalDestination.TRANSFERRED_OFFLINE_INVENTORY)
                .mapToInt(DefectGroupRequest.ScrapPartRequest::quantity)
                .sum();
    }
}

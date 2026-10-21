package com.yumi.production.api;

/**
 * 任务明细请求：一条明细直接对应一个生产需求与当前履约来源。
 *
 * <p>其余字段（产品、快照、目标工序、标准分钟）一律由来源与该来源所属需求的冻结依据解析，
 * 不接受调用方传入，避免页面伪造金额或工时。
 */
public record TaskItemRequest(String productionSourceBusinessId, int plannedQuantity) {

    public TaskItemRequest {
        if (productionSourceBusinessId == null || productionSourceBusinessId.isBlank()) {
            throw new IllegalArgumentException("任务明细必须关联履约生产来源");
        }
        if (plannedQuantity <= 0) {
            throw new IllegalArgumentException("任务明细计划数量必须大于 0");
        }
    }
}

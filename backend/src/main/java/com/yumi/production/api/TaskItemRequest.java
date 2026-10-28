package com.yumi.production.api;

/**
 * 任务明细请求：一条明细直接对应一个生产需求与当前履约来源。
 *
 * <p>正常/超额明细指定生产来源；返工明细可以同时指定返工来源，并由服务端按返工来源解析
 * 原生产来源。其余字段（产品、快照、目标工序、标准分钟）一律由来源与该来源所属需求的
 * 冻结依据解析，不接受调用方传入，避免页面伪造金额或工时。
 */
public record TaskItemRequest(
        String productionSourceBusinessId, String repairSourceBusinessId, int plannedQuantity) {

    public TaskItemRequest(String productionSourceBusinessId, int plannedQuantity) {
        this(productionSourceBusinessId, null, plannedQuantity);
    }

    public TaskItemRequest {
        boolean hasProduction = productionSourceBusinessId != null
                && !productionSourceBusinessId.isBlank();
        boolean hasRepair = repairSourceBusinessId != null && !repairSourceBusinessId.isBlank();
        if (!hasProduction && !hasRepair) {
            throw new IllegalArgumentException("任务明细必须关联履约生产来源或返工来源");
        }
        if (!hasRepair) {
            repairSourceBusinessId = null;
        }
        if (plannedQuantity <= 0) {
            throw new IllegalArgumentException("任务明细计划数量必须大于 0");
        }
    }
}

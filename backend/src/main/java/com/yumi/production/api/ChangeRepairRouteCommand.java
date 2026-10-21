package com.yumi.production.api;

/** 修复终止缺口转路线命令：只转换 production 当前未解决缺口。 */
public record ChangeRepairRouteCommand(String repairDemandBusinessId, int quantity) {

    public ChangeRepairRouteCommand {
        if (repairDemandBusinessId == null || repairDemandBusinessId.isBlank()) {
            throw new IllegalArgumentException("转路线必须指定修复需求");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("转路线数量必须大于零");
        }
    }
}

package com.yumi.production.api;

/** 售后补发需求建立命令。 */
public record CreateAfterSalesReplacementDemandCommand(
        String repairDemandBusinessId, String successorObligationBusinessId, int quantity) {

    public CreateAfterSalesReplacementDemandCommand {
        if (repairDemandBusinessId == null || repairDemandBusinessId.isBlank()) {
            throw new IllegalArgumentException("补发需求必须引用原修复需求");
        }
        if (successorObligationBusinessId == null || successorObligationBusinessId.isBlank()) {
            throw new IllegalArgumentException("补发需求必须关联继任售后义务");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("补发数量必须大于零");
        }
    }
}

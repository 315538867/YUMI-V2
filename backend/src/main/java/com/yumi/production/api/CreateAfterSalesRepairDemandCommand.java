package com.yumi.production.api;

/** 售后修复需求建立命令。 */
public record CreateAfterSalesRepairDemandCommand(
        String returnedSourceBusinessId, String afterSalesObligationBusinessId, int quantity) {

    public CreateAfterSalesRepairDemandCommand {
        if (returnedSourceBusinessId == null || returnedSourceBusinessId.isBlank()) {
            throw new IllegalArgumentException("修复需求必须引用退回制品来源");
        }
        if (afterSalesObligationBusinessId == null || afterSalesObligationBusinessId.isBlank()) {
            throw new IllegalArgumentException("修复需求必须关联售后义务");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("修复数量必须大于零");
        }
    }
}

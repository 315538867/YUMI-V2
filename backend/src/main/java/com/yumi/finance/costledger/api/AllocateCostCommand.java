package com.yumi.finance.costledger.api;

import com.yumi.platform.api.value.CostAmount;
import java.util.List;

/** 显式成本分摊：合计必须等于成本金额。 */
public record AllocateCostCommand(
        String idempotencyKey, String costEntryBusinessId, List<AllocationRequest> allocations) {

    public AllocateCostCommand {
        allocations = List.copyOf(allocations == null ? List.of() : allocations);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("成本分摊必须携带幂等键");
        }
        if (costEntryBusinessId == null || costEntryBusinessId.isBlank()) {
            throw new IllegalArgumentException("成本分摊必须指定成本事实");
        }
        if (allocations.isEmpty()) {
            throw new IllegalArgumentException("成本分摊必须至少一条");
        }
    }

    public record AllocationRequest(
            String subjectType, String subjectBusinessId, CostAmount amount) {

        public AllocationRequest {
            if (subjectType == null || subjectType.isBlank()
                    || subjectBusinessId == null || subjectBusinessId.isBlank()) {
                throw new IllegalArgumentException("分摊必须指定承担主体");
            }
            if (amount == null) {
                throw new IllegalArgumentException("分摊金额不能为空");
            }
        }
    }
}

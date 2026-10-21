package com.yumi.finance.cash.api;

import com.yumi.platform.api.value.SettlementMoney;
import java.time.LocalDate;
import java.util.List;

/** 工资付款：一次现金分配到一个或多个员工结果。 */
public record PayPayrollCommand(
        String idempotencyKey, List<PayrollAllocation> allocations, LocalDate businessDate,
        List<String> voucherFileRefs) {

    public PayPayrollCommand(
            String idempotencyKey, List<PayrollAllocation> allocations, LocalDate businessDate) {
        this(idempotencyKey, allocations, businessDate, List.of());
    }

    public PayPayrollCommand {
        allocations = List.copyOf(allocations == null ? List.of() : allocations);
        voucherFileRefs = List.copyOf(voucherFileRefs == null ? List.of() : voucherFileRefs);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("工资付款必须携带幂等键");
        }
        if (allocations.isEmpty()) {
            throw new IllegalArgumentException("工资付款必须至少一个员工分配");
        }
        if (businessDate == null) {
            throw new IllegalArgumentException("工资付款必须填写业务日期");
        }
    }

    public record PayrollAllocation(String resultBusinessId, SettlementMoney amount) {
        public PayrollAllocation {
            if (resultBusinessId == null || resultBusinessId.isBlank()) {
                throw new IllegalArgumentException("工资分配必须指定员工结果");
            }
            if (amount == null || amount.value().signum() <= 0) {
                throw new IllegalArgumentException("工资分配金额必须大于零");
            }
        }
    }
}

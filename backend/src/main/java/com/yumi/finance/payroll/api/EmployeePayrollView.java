package com.yumi.finance.payroll.api;

import com.yumi.platform.api.value.SettlementMoney;
import java.util.List;

/** 员工工资参考视图。 */
public record EmployeePayrollView(
        String resultBusinessId,
        String periodBusinessId,
        String employeeBusinessId,
        String employeeNo,
        String employeeName,
        String status,
        String referenceRunBusinessId,
        String referenceFingerprint,
        SettlementMoney confirmedAmount,
        SettlementMoney referenceAmount,
        SettlementMoney payableAmount,
        SettlementMoney paidAmount,
        SettlementMoney remainingAmount,
        long rowVersion,
        List<ReferenceLineView> lines) {

    public record ReferenceLineView(
            String lineBusinessId,
            String lineType,
            String sourceBusinessId,
            String componentCode,
            Integer quantity,
            java.math.BigDecimal unitAmount,
            SettlementMoney referenceAmount,
            boolean occurred,
            boolean estimated,
            String materialSource,
            boolean incomplete,
            String note) {
    }
}

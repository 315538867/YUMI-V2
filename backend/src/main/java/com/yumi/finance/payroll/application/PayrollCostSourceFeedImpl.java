package com.yumi.finance.payroll.application;

import com.yumi.finance.payroll.api.PayrollCostSourceFeed;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 工资实际人工成本来源实现。 */
public class PayrollCostSourceFeedImpl implements PayrollCostSourceFeed {

    private final PayrollRepository payroll;

    public PayrollCostSourceFeedImpl(PayrollRepository payroll) {
        this.payroll = payroll;
    }

    @Override
    public List<DirectLaborCostItem> listDirectLaborCosts(LocalDate start, LocalDate end) {
        List<DirectLaborCostItem> items = new ArrayList<>();
        payroll.listDirectLaborCosts(start, end).forEach(row -> items.add(
                new DirectLaborCostItem(
                        row.costSourceBusinessId(), row.resultBusinessId(),
                        row.employeeBusinessId(), row.amount(), row.businessDate(), "ACCRUE",
                        row.createdAt())));
        payroll.listCostAdjustments(start, end).forEach(row -> items.add(
                new DirectLaborCostItem(
                        row.adjustmentBusinessId(), row.resultBusinessId(),
                        row.employeeBusinessId(), row.amountDelta(), row.businessDate(), "ADJUST",
                        row.createdAt())));
        items.sort(Comparator.comparing(DirectLaborCostItem::occurredAt)
                .thenComparing(DirectLaborCostItem::sourceBusinessId));
        return items;
    }
}

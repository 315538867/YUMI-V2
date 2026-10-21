package com.yumi.calculation.api;

import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.SettlementMoney;
import java.util.List;

/**
 * 金额均分：把小组总额按最小货币单位分配给稳定排序的成员。
 *
 * <p>规则来源：`docs/一期/07-计算中心模块.md` §4.7 与 `04-公共业务与技术规则.md` §8：
 * <pre>
 * 总额转换为最小单位整数
 * 基础份额 = floor(整数总额 ÷ 成员数)
 * 余数 = 整数总额 mod 成员数
 * 按员工稳定业务标识升序，前“余数”名各增加一个最小单位
 * </pre>
 * 分配合计必须严格等于总额；零成员明确拒绝。
 */
public interface MoneyAllocationCalculator {

    /**
     * 按分（2 位）均分结算金额。
     *
     * @param total 待分配总额
     * @param orderedEmployeeIds 已按稳定业务标识升序排列的员工标识
     */
    AllocationResult splitEqually(SettlementMoney total, List<String> orderedEmployeeIds);

    /**
     * 按 0.0001 元均分成本金额。
     *
     * <p>仅用于成本参考行到人的等分；胶水减免另按重量分配，不使用本方法。
     */
    CostAllocationResult splitCostEqually(CostAmount total, List<String> orderedEmployeeIds);

    /** 便捷入口：无状态实现。 */
    static MoneyAllocationCalculator create() {
        return com.yumi.calculation.MoneyAllocationCalculatorImpl.create();
    }
}

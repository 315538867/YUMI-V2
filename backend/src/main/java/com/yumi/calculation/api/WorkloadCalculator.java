package com.yumi.calculation.api;

/**
 * 个人与小组工作量纯计算。
 *
 * <p>规则来源：`docs/一期/07-计算中心模块.md` §4.3—§4.5。
 * 接口只做整数秒运算，不读取数据库、不读取系统时间，也不做业务资格判断。
 */
public interface WorkloadCalculator {

    WorkloadResult assessIndividual(WorkloadInput input);

    TeamWorkloadResult assessTeam(TeamWorkloadInput input);

    static WorkloadCalculator create() {
        return new com.yumi.calculation.WorkloadCalculatorImpl();
    }
}

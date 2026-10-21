package com.yumi.calculation.api;

/**
 * 月度胶水减免纯计算。
 *
 * <p>规则来源：`docs/一期/07-计算中心模块.md` §4.10 与本提案 CA-04、CA-05。
 *
 * <p>只做纯计算：不读数据库、不读系统时间、不维护"剩余额度账户"。自然月不等于工资期间；
 * 配置版本与责任归月由财务负责，计算中心只按传入版本与行集合计算。
 */
public interface MonthlyGlueAllowanceCalculator {

    MonthlyGlueAllowanceResult calculate(MonthlyGlueAllowanceInput input);

    static MonthlyGlueAllowanceCalculator create() {
        return new com.yumi.calculation.MonthlyGlueAllowanceCalculatorImpl();
    }
}

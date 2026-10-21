package com.yumi.calculation.api;

/**
 * 商品单日最大产能输入：模具数量与模具每日生产批次，均来自商品基本字段。
 *
 * <p>结果只表示新制制作上限；不乘小组效率、不乘员工人数，也不约束修补、捏毛装袋、缝边剪袋和
 * 其他任务（基线 07 §4.1）。
 */
public record DailyCapacityInput(int moldCount, int batchesPerMoldPerDay) {

    public DailyCapacityInput {
        if (moldCount < 0) {
            throw new IllegalArgumentException("模具数量不允许为负：" + moldCount);
        }
        if (batchesPerMoldPerDay <= 0) {
            throw new IllegalArgumentException("模具每日生产批次必须为正数：" + batchesPerMoldPerDay);
        }
    }
}

package com.yumi.calculation.api;

import com.yumi.platform.api.value.DurationMinutes;
import com.yumi.platform.api.value.DurationSeconds;
import com.yumi.platform.api.value.Quantity;
import java.util.List;

/**
 * 个人或小组任务的工作量输入。
 *
 * <p>规则来源：`docs/一期/07-计算中心模块.md` §4.3、§4.4：
 * <ul>
 *   <li>生产/超额/修复任务按<b>任务明细</b>计算：计划数量 × 冻结单件标准分钟 × 60，再汇总；</li>
 *   <li>其他任务的标准分钟由命令直接提供，表示整个任务预计经过时间，不再乘数量；</li>
 *   <li>小组只在整项任务级除一次效率并向上取整，不逐明细取整。</li>
 * </ul>
 *
 * @param taskKind 任务类型
 * @param items 任务明细；其他任务必须为空
 * @param otherTaskStandardMinutes 其他任务整体标准分钟；非其他任务必须为空
 * @param dailyEffectiveMinutes 每日有效分钟，用于计算剩余负载
 * @param occupiedSecondsBefore 创建前已占用秒数（已核验 + 未核验预留）
 */
public record WorkloadInput(
        TaskKind taskKind,
        List<ItemStandard> items,
        DurationMinutes otherTaskStandardMinutes,
        DurationMinutes dailyEffectiveMinutes,
        DurationSeconds occupiedSecondsBefore,
        boolean team) {

    public WorkloadInput {
        if (taskKind == null) {
            throw new IllegalArgumentException("任务类型不能为空");
        }
        items = items == null ? List.of() : List.copyOf(items);
        if (dailyEffectiveMinutes == null) {
            throw new IllegalArgumentException("每日有效分钟不能为空");
        }
        if (occupiedSecondsBefore == null) {
            throw new IllegalArgumentException("创建前已占用秒数不能为空（无占用请传 0）");
        }
        if (taskKind == TaskKind.OTHER_TASK) {
            if (otherTaskStandardMinutes == null) {
                throw new IllegalArgumentException("其他任务必须提供整体标准分钟");
            }
            if (!items.isEmpty()) {
                throw new IllegalArgumentException("其他任务不使用任务明细标准分钟");
            }
        } else {
            if (otherTaskStandardMinutes != null) {
                throw new IllegalArgumentException("生产/修复任务不能使用其他任务整体标准分钟");
            }
            if (items.isEmpty()) {
                throw new IllegalArgumentException("生产/修复任务必须至少有一条明细");
            }
        }
    }

    /** 单条任务明细：计划数量与冻结的单件标准分钟。 */
    public record ItemStandard(Quantity plannedQuantity, DurationMinutes unitStandardMinutes) {

        public ItemStandard {
            if (plannedQuantity == null) {
                throw new IllegalArgumentException("明细计划数量不能为空");
            }
            if (unitStandardMinutes == null) {
                throw new IllegalArgumentException("明细单件标准分钟不能为空");
            }
            if (unitStandardMinutes.value() <= 0) {
                throw new IllegalArgumentException("单件标准分钟必须大于 0");
            }
        }

        /** 明细基准秒数 = 计划数量 × 单件标准分钟 × 60。 */
        public DurationSeconds baseSeconds() {
            long seconds = Math.multiplyExact(plannedQuantity.value(), (long) unitStandardMinutes.value() * 60L);
            return DurationSeconds.of(seconds);
        }
    }
}

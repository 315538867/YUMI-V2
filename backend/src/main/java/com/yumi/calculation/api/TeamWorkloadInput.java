package com.yumi.calculation.api;

import com.yumi.platform.api.value.DurationSeconds;
import com.yumi.platform.api.value.EfficiencyFactor;
import java.util.List;

/**
 * 小组任务工作量输入：在个人输入基础上增加效率因子与成员剩余秒数。
 *
 * <p>规则来源：`docs/一期/07-计算中心模块.md` §4.4：
 * <ul>
 *   <li>效率因子对整项小组任务只应用一次，仅最终结果向上取整；</li>
 *   <li>小组可用秒数取所有成员剩余秒数的最小值；</li>
 *   <li>其他小组任务的标准分钟已是整体经过时间，不再除以效率。</li>
 * </ul>
 *
 * @param base 任务与明细输入（必须声明 {@code team = true}）
 * @param efficiencyFactor 效率因子；适用效率的任务必须提供
 * @param memberOccupiedSeconds 各成员已占用秒数；顺序不影响结果
 */
public record TeamWorkloadInput(
        WorkloadInput base, EfficiencyFactor efficiencyFactor, List<DurationSeconds> memberOccupiedSeconds) {

    public TeamWorkloadInput {
        if (base == null) {
            throw new IllegalArgumentException("小组任务基础输入不能为空");
        }
        if (!base.team()) {
            throw new IllegalArgumentException("小组工作量输入必须声明 team = true");
        }
        memberOccupiedSeconds = memberOccupiedSeconds == null ? List.of() : List.copyOf(memberOccupiedSeconds);
        if (memberOccupiedSeconds.isEmpty()) {
            throw new IllegalArgumentException("小组任务必须有至少一名成员");
        }
        if (base.taskKind().appliesEfficiency() && efficiencyFactor == null) {
            throw new IllegalArgumentException("生产/修复小组任务必须提供效率因子");
        }
        if (!base.taskKind().appliesEfficiency() && efficiencyFactor != null) {
            throw new IllegalArgumentException("其他小组任务不使用效率因子");
        }
    }
}

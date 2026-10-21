package com.yumi.production.api;

/**
 * 生产任务缩减参与接口（订单减量与取消在同一事务内调用）。
 *
 * <p>规则来源：`docs/一期/12-订单模块.md` §7.2 与 `docs/一期/11-生产作业模块.md` §5.6、§7.3：
 * <ul>
 *   <li>只缩减该来源尚未核验的分配，已形成生产事实的数量不动；</li>
 *   <li>混合来源任务只调整目标来源明细，其他来源保持原样；</li>
 *   <li>剩余数量为零的明细转为取消；整任务剩余为零时追加取消事实并释放日槽位；</li>
 *   <li>同步释放人员计划负载、商品新制产能预留与来源活动预留；</li>
 *   <li>没有任务占用的数量（尚未排产）不属于任务缩减范围，按实际存在的分配释放。</li>
 * </ul>
 */
public interface ProductionTaskAdjustmentParticipant {

    ShrinkUnverifiedResult shrinkUnverified(ShrinkUnverifiedCommand command);
}

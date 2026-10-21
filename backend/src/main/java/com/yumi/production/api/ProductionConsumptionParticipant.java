package com.yumi.production.api;

/**
 * 生产消费参与接口（订单与售后在同一事务内调用）。
 *
 * <p>规则来源：`docs/一期/11-生产作业模块.md` §5.2.1、§5.6、§7.7：
 * <ul>
 *   <li>只能消费目标工序已合格就绪、未被活动任务预留、未被移交的数量；</li>
 *   <li>已移交线下的数量不能取回，也不能被消费；</li>
 *   <li>反向消费只冲销有效消费，不改变已发生的生产、质量与劳动事实；</li>
 *   <li>消费数量不足即拒绝，不用 {@code max(0)} 截断。</li>
 * </ul>
 */
public interface ProductionConsumptionParticipant {

    ConsumptionPreview prepareConsume(ConsumeProductionCommand command);

    ConsumptionResult commitConsume(ConsumptionPreview prepared);

    ConsumptionPreview prepareReverse(ReverseConsumptionCommand command);

    ConsumptionResult commitReverse(ConsumptionPreview prepared);
}

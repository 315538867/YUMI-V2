package com.yumi.orders.api;

/**
 * 订单变更（基线 12 §7.2）。
 *
 * <p>规则：
 * <ul>
 *   <li>先分类，再决定是否触碰生产定义；禁止覆盖原约定与原生产需求；</li>
 *   <li>仅价格/收件人变化只追加约定版本与金额调整；</li>
 *   <li>仅数量变化按增量/减量调整需求，已制部分必须同事务转线下；</li>
 *   <li>生产定义变化建立继任订单项、继任约定组与新需求，原需求等额减少；</li>
 *   <li>缩短或取消同事务释放已有任务的未核验预留，只影响目标来源。</li>
 * </ul>
 */
public interface OrderChangeService {

    OrderChangeRef change(ChangeConfirmedOrderCommand command);
}

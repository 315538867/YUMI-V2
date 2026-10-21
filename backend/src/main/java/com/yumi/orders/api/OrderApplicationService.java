package com.yumi.orders.api;

/**
 * 订单命令（基线 12 §7.1、§10）。
 *
 * <p>确认规则：
 * <ul>
 *   <li>命令成功即为 {@code CONFIRMED}，不保存半成品；</li>
 *   <li>客户快照、商品/方案快照与生效生产依据在同一事务内冻结；</li>
 *   <li>每个订单项建立一个交付约定组与一个生产需求，需求携带同一依据与指纹；</li>
 *   <li>任何参与端失败整笔回滚，不留下半条订单；</li>
 *   <li>同一幂等键重试复用已成功结果，不重复建单。</li>
 * </ul>
 */
public interface OrderApplicationService {

    OrderRef createAndConfirm(CreateConfirmedOrderCommand command);
}

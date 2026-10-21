package com.yumi.orders.api;

/**
 * 发货与发货更正（基线 12 §7.3、§8.3）。
 *
 * <p>规则：
 * <ul>
 *   <li>发货直接确认，不建立草稿或长期预留；</li>
 *   <li>发货必须消费达到约定目标工序且质量合格的生产来源，消费与发货事实同事务；</li>
 *   <li>已确认发货不能直接编辑，错误通过发货更正事实；</li>
 *   <li>发货保存当时的收货信息，历史记录不回读客户当前值；</li>
 *   <li>并发发货不得超出来源可用数量，由来源锁与消费事实共同保证。</li>
 * </ul>
 */
public interface OrderDeliveryService {

    ShipmentRef confirmShipment(ConfirmShipmentCommand command);

    ShipmentRef correctShipment(CorrectShipmentCommand command);
}

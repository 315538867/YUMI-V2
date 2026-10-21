/**
 * 订单模块：交易约定、应收退款、发货、履约、完成、售后案例与售后义务。
 *
 * <p>边界（基线 12、03 §5.1）：
 * <ul>
 *   <li>内部子域：trade、fulfillment、receivable、aftersales；售后不覆盖原订单、发货或生产事实。</li>
 *   <li>不依赖 finance；订单拥有义务与资格，财务执行真实收付并调用订单支付参与接口。</li>
 *   <li>售后成本以不可变来源 Feed 发布，由 finance 下游归集。</li>
 * </ul>
 *
 * <p>依赖方向：orders → platform、customer、product、production。
 */
package com.yumi.orders;

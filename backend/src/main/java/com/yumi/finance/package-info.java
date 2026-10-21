/**
 * 财务结算模块：逐人工资核定、成本台账、真实收付、支付分配、冲销与月度汇总。
 *
 * <p>边界（基线 13、03 §5.2）：
 * <ul>
 *   <li>内部子域：payroll、costledger、cash；同模块内也不得直接写对方表。</li>
 *   <li>工资应付不等于人工成本；成本发生不等于现金支付；业务义务不等于真实现金。</li>
 *   <li>生产与售后成本通过下游 Feed 归集，不要求上游同事务。</li>
 * </ul>
 *
 * <p>依赖方向：finance → platform、calculation、product、workforce、production、orders。
 */
package com.yumi.finance;

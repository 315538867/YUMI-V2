/**
 * 报表模块：消费各模块公开 ReportingFeed，构建可重建只读投影、来源对账与月度汇总。
 *
 * <p>边界（基线 14）：
 * <ul>
 *   <li>只读公开 Feed，不跨模块 SQL 取数，不参与业务资格判断。</li>
 *   <li>投影损坏或延迟不得影响业务真相；重建使用影子版本并原子切换。</li>
 * </ul>
 *
 * <p>依赖方向：reporting → platform 及各业务模块的 api。
 */
package com.yumi.reporting;

package com.yumi.production.api;

/**
 * 生产事实更正（基线 11 §7.8）。
 *
 * <p>规则：
 * <ul>
 *   <li>只更正有效核验事实，历史事实保留并置为无效；</li>
 *   <li>报废改为非报废时只反向旧责任，不再生成新责任事实；</li>
 *   <li>劳动、材料与责任各自发布 REVERSE / APPLY，构成一个完整命令批次；</li>
 *   <li>历史负载不搬移：调整始终落在原任务工作日期与主体下。</li>
 * </ul>
 */
public interface ProductionCorrectionService {

    ProductionCorrectionResult correct(ProductionCorrectionCommand command);
}

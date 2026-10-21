package com.yumi.production.api;

/**
 * 已有订单生产来源的一次性期初初始化（内部参与接口，不开放网页命令）。
 *
 * <p>规则来源：`docs/一期/11-生产作业模块.md` §5.5、§7.9：
 * <ul>
 *   <li>只记录当前状态，不创建历史任务、执行人员、劳动、材料或责任来源；</li>
 *   <li>各状态数量合计必须等于来源接管数量；</li>
 *   <li>每个来源只能初始化一次；</li>
 *   <li>初始化后达到目标工序的合格数量可正常消费，未制数量可正常安排。</li>
 * </ul>
 */
public interface ProductionOpeningApplicationService {

    ProductionOpeningRef initialize(InitializeProductionOpeningCommand command);
}

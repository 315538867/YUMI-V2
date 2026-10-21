package com.yumi.production.api;

/**
 * 生产需求参与接口（订单与售后在同一事务内调用）。
 *
 * <p>规则来源：`docs/一期/11-生产作业模块.md` §5.1、§5.2、§5.5：
 * <ul>
 *   <li>一个订单项 = 一个生产需求 = 一个产品 + 一个方案快照 + 一个目标工序 + 一组数量；</li>
 *   <li>需求来源类型与需求类型分别表达来源与履约方式，不得互相替代；</li>
 *   <li>建立需求不产生任务、不写排班；</li>
 *   <li>同一来源重复建立返回同一需求（幂等），不重复增加需求数量。</li>
 * </ul>
 */
public interface ProductionDemandParticipant {

    PreparedDemand prepareCreate(CreateProductionDemandRequest request);

    ProductionDemandRef commitCreate(PreparedDemand prepared);

    /** 订单减量预检：只读校验，返回分类数量与阻塞原因。 */
    DemandReductionPreview previewReduction(ReduceProductionDemandCommand command);

    /** 提交订单减量：未制取消与已制移交必须同事务完成。 */
    DemandReductionResult commitReduction(ReduceProductionDemandCommand command);
}

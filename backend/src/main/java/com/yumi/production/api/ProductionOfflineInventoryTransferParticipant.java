package com.yumi.production.api;

/**
 * 线下库存单向移交参与接口。
 *
 * <p>规则来源：`docs/一期/11-生产作业模块.md` §5.7：
 * <ul>
 *   <li>只移交已形成实际制品、无活动任务占用与未完成消费的数量；</li>
 *   <li>移交与终态退出共享唯一退出标识，来源只扣减一次、缺口不重复增加；</li>
 *   <li>移交全部字段提交后不可在系统内更正，录入错误只能线下处理；</li>
 *   <li>移交不冲销已真实发生的生产、质量、劳动与成本事实。</li>
 * </ul>
 */
public interface ProductionOfflineInventoryTransferParticipant {

    TransferPreview prepareTransfer(TransferProductionToOfflineCommand command);

    /**
     * 提交移交：命令字段（原因、库存名称、交接人、时间）必须与准备结果一起提交，
     * 防止准备后篡改。
     */
    TransferResult commit(TransferProductionToOfflineCommand command, TransferPreview prepared);
}

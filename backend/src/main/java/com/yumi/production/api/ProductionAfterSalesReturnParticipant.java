package com.yumi.production.api;

/**
 * 售后退回制品参与接口（orders 在同一事务内调用）。
 *
 * <p>规则来源：`docs/一期/11-生产作业模块.md` §7.11、§7.12：
 * <ul>
 *   <li>退回接收按最近交付消费重入物理根，不增加累计制作量；</li>
 *   <li>池内处置只能处理可用数量，不伪造任务、劳动或材料来源；</li>
 *   <li>原样退还同事务写池来源消费；报废/义务释放写唯一退出，转线下另写单向移交。</li>
 * </ul>
 */
public interface ProductionAfterSalesReturnParticipant {

    PreparedReturnReentry prepareRegisterReturn(RegisterReturnCommand command);

    ReturnReentryResult commitRegisterReturn(PreparedReturnReentry prepared);

    PreparedReturnDisposition prepareDisposition(ReturnDispositionCommand command);

    /** 某退回制品来源当前池内可用数量。 */
    int availablePoolQuantity(String returnedSourceBusinessId);

    ReturnDispositionResult commitDisposition(
            ReturnDispositionCommand command, PreparedReturnDisposition prepared);
}

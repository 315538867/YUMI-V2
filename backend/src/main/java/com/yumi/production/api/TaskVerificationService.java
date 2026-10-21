package com.yumi.production.api;

/**
 * 生产核验（基线 11 §7.4、§7.5、§7.6、§7.7）。
 *
 * <p>关键规则：
 * <ul>
 *   <li>每条明细只能提交一次最终核验；未完成数量释放占用回到来源可安排数量；</li>
 *   <li>合格/返工/报废三选一，线上报废再按实物去向拆分，去向合计等于报废数量；</li>
 *   <li>缺陷事实按原因分组，合计严格等于返工与报废之和；</li>
 *   <li>只有报废进入责任认定，责任数量等于该报废明细数量，不因去向免扣；</li>
 *   <li>返工生成可占用的返工来源，不增加累计制作量；</li>
 *   <li>{@code OTHER_TASK} 不产生制品数量、阶段或材料成本来源。</li>
 * </ul>
 */
public interface TaskVerificationService {

    VerificationView verify(VerifyTaskCommand command);

    VerificationView verifyOtherTask(VerifyOtherTaskCommand command);
}

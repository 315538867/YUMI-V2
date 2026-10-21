package com.yumi.production.api;

/**
 * 缺陷原因维护（基线 11 §7.6）。
 *
 * <p>一期不设缺陷等级，也不根据原因自动计算工资、扣款或成本；停用原因不得用于新核验，
 * 历史事实继续展示核验时保存的原因编号与名称。
 */
public interface DefectReasonAdministrationService {

    DefectReasonView save(SaveDefectReasonCommand command);

    DefectReasonView changeStatus(ChangeDefectReasonStatusCommand command);
}

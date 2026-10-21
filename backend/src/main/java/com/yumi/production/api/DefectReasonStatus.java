package com.yumi.production.api;

/** 缺陷原因状态：停用后不得用于新核验，历史事实继续展示核验时保存的原因。 */
public enum DefectReasonStatus {
    ACTIVE,
    INACTIVE
}

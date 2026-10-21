package com.yumi.production.api;

/** 期初接管时数量所在阶段。 */
public enum OpeningStageCode {
    NOT_STARTED,
    MAKING,
    BAGGING,
    SEAM_CUTTING,
    REWORK_REQUIRED,
    SCRAPPED
}

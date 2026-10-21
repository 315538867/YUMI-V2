package com.yumi.calculation.api;

/** 三道生产工序；装箱/打包发货不属于生产工序。 */
public enum WorkProcedure {
    MAKING,
    BAGGING,
    SEAM_CUTTING
}

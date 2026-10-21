package com.yumi.production.api;

/** 报废实物去向：实际销毁或转线下库存。 */
public enum PhysicalDestination {
    DESTROYED,
    TRANSFERRED_OFFLINE_INVENTORY,
    /** 非报废路径：制品仍在系统内（返工/修补）。 */
    IN_SYSTEM,
    /** 退回制品原样退还客户：已离开系统。 */
    CONSUMED
}

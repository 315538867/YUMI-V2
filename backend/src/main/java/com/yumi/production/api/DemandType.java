package com.yumi.production.api;

/** 需求类型：新制或修复退回原制品。 */
public enum DemandType {
    NEW_PRODUCT,
    RETURNED_PRODUCT_REPAIR;

    /** 是否通过制作新增物理制品。 */
    public boolean createsPhysicalProduct() {
        return this == NEW_PRODUCT;
    }
}

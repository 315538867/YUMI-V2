package com.yumi.production.api;

/** 生产目标工序。 */
public enum TargetProcess {
    MAKING,
    BAGGING,
    SEAM_CUTTING;

    /** 工序链：从制作到目标。 */
    public java.util.List<TargetProcess> chainTo() {
        return switch (this) {
            case MAKING -> java.util.List.of(MAKING);
            case BAGGING -> java.util.List.of(MAKING, BAGGING);
            case SEAM_CUTTING -> java.util.List.of(MAKING, BAGGING, SEAM_CUTTING);
        };
    }

    /** 是否存在客户供料选项：缝边剪袋没有。 */
    public boolean supportsCustomerSupply() {
        return this != SEAM_CUTTING;
    }
}

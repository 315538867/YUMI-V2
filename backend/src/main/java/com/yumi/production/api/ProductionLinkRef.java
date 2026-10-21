package com.yumi.production.api;

/** 跨模块来源引用：模块 + 类型 + 稳定业务标识。 */
public record ProductionLinkRef(String sourceModule, String sourceType, String sourceBusinessId) {

    public ProductionLinkRef {
        if (sourceModule == null || sourceModule.isBlank()
                || sourceType == null || sourceType.isBlank()
                || sourceBusinessId == null || sourceBusinessId.isBlank()) {
            throw new IllegalArgumentException("来源模块、类型与业务标识不能为空");
        }
    }
}

package com.yumi.workforce.api;

/**
 * 活动引用来源：只表达来源模块、来源类型与稳定业务标识。
 *
 * <p>由 workforce 定义，不依赖 production 类型；生产任务、订单缩减与售后义务变更都通过它表达。
 */
public record WorkforceActivitySourceRef(String sourceModule, String sourceType, String sourceBusinessId) {

    public WorkforceActivitySourceRef {
        if (sourceModule == null || sourceModule.isBlank()) {
            throw new IllegalArgumentException("来源模块不能为空");
        }
        if (sourceType == null || sourceType.isBlank()) {
            throw new IllegalArgumentException("来源类型不能为空");
        }
        if (sourceBusinessId == null || sourceBusinessId.isBlank()) {
            throw new IllegalArgumentException("来源业务标识不能为空");
        }
    }
}

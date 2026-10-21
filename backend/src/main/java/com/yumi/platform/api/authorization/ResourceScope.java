package com.yumi.platform.api.authorization;

import java.util.Objects;

/**
 * 资源范围：权限校验的作用对象。
 *
 * <p>一期只有“全局范围”和“具体资源实例”两种，避免在没有多组织模型时虚构租户层级。
 *
 * @param type 资源类型稳定代码，例如 {@code payroll.period}
 * @param businessId 资源稳定业务标识；全局范围时为空
 */
public record ResourceScope(String type, String businessId) {

    /** 全局范围：仅校验权限点本身。 */
    public static final ResourceScope GLOBAL = new ResourceScope("global", null);

    public ResourceScope {
        Objects.requireNonNull(type, "资源类型不能为空");
        if (type.isBlank()) {
            throw new IllegalArgumentException("资源类型不能为空白");
        }
    }

    public static ResourceScope of(String type, String businessId) {
        return new ResourceScope(type, businessId);
    }

    public boolean isGlobal() {
        return businessId == null;
    }
}

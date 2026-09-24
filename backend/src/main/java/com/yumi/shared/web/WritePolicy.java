package com.yumi.shared.web;

import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

/**
 * 写命令策略：/api/ 下的写方法默认要求幂等键并记录写审计；
 * 仅登记在册的只读命令按**精确方法 + 精确路径**豁免，不使用后缀或通配匹配。
 */
public final class WritePolicy {

    private static final String PREVIEW_PATH = "/api/products/preview";
    private static final Pattern PRODUCT_PREVIEW_PATH = Pattern.compile("^/api/products/\\d+/preview$");

    private WritePolicy() {
    }

    /**
     * 商品只读试算：{@code POST /api/products/preview} 与 {@code POST /api/products/{id}/preview}。
     * 两者不写幂等记录与业务写审计，但仍要求认证、安全日志与 requestId。
     */
    public static boolean isReadOnlyPreview(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return false;
        }
        var uri = request.getRequestURI();
        return PREVIEW_PATH.equals(uri) || PRODUCT_PREVIEW_PATH.matcher(uri).matches();
    }
}

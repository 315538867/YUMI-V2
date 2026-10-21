package com.yumi.platform.api.file;

import java.util.Set;

/**
 * 上传校验策略。
 *
 * <p>大小上限与允许的媒体类型属于部署参数（基线 06 §13），此处给出可配置的契约与安全默认值：
 * 默认只允许图片与 PDF，单文件上限 10 MB。
 */
public record FilePolicy(long maxSizeBytes, Set<String> allowedMediaTypes) {

    public static final long DEFAULT_MAX_SIZE_BYTES = 10L * 1024 * 1024;

    public static final Set<String> DEFAULT_ALLOWED_MEDIA_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp", "application/pdf");

    public FilePolicy {
        if (maxSizeBytes <= 0) {
            throw new IllegalArgumentException("文件大小上限必须为正数");
        }
        allowedMediaTypes = Set.copyOf(allowedMediaTypes);
        if (allowedMediaTypes.isEmpty()) {
            throw new IllegalArgumentException("允许的媒体类型不能为空");
        }
    }

    public static FilePolicy defaults() {
        return new FilePolicy(DEFAULT_MAX_SIZE_BYTES, DEFAULT_ALLOWED_MEDIA_TYPES);
    }
}

package com.yumi.files;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import jakarta.servlet.MultipartConfigElement;

/**
 * Multipart 上限（派生决策：业务上限 5MB，解析上限放宽到 6MB，
 * 使“超 5MB”由业务校验以 VALIDATION_INVALID 400 拒绝而非容器异常）。
 */
@Configuration
public class FilesMultipartConfiguration {

    @Bean
    MultipartConfigElement fileMultipartConfigElement() {
        var tmpDir = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "yumi-multipart");
        return new MultipartConfigElement(tmpDir.toString(),
                6L * 1024 * 1024, 8L * 1024 * 1024, 0);
    }
}

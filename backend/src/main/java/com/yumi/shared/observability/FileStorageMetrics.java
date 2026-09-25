package com.yumi.shared.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件存储可写性指标（任务 9.5）：暴露 `yumi_file_storage_writable`（1 = 可写，0 = 不可写）。
 * 取值函数在**每次抓取时**实时判定（实际建删一个探针文件），与
 * {@link FileStorageHealthIndicator} 共用同一口径，便于直接写告警规则。
 */
@Component
public class FileStorageMetrics {

    public static final String METRIC = "yumi.file.storage.writable";

    private final Path root;

    public FileStorageMetrics(MeterRegistry registry,
                              @Value("${YUMI_FILES_DIR:/tmp/yumi-files}") String filesDir) {
        this.root = Path.of(filesDir);
        Gauge.builder(METRIC, this, FileStorageMetrics::writableValue)
                .description("文件存储目录可写性：1 可写 / 0 不可写")
                .register(registry);
    }

    /** 实时判定：能建删探针文件即可写；任何异常都按不可写处理（只记录类型，不泄露路径以外信息）。 */
    public double writableValue() {
        try {
            Files.createDirectories(root);
            Path probe = Files.createTempFile(root, ".metrics-", ".probe");
            Files.deleteIfExists(probe);
            return 1.0;
        } catch (Exception error) {
            return 0.0;
        }
    }
}

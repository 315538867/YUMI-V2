package com.yumi.shared.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件存储可写性健康检查（任务 9.5「文件不可写」）：在 `${YUMI_FILES_DIR}` 下实际创建并删除一个探针文件，
 * 失败则报 `DOWN` 并给出目录与错误类型（不泄露路径以外的敏感信息）。
 * 只作为 `/api/actuator/health` 的独立组件暴露，**不并入 readiness 组**——避免磁盘只读把整个服务判为不可接流量。
 */
@Component("fileStorage")
public class FileStorageHealthIndicator implements HealthIndicator {

    private final Path root;

    public FileStorageHealthIndicator(@Value("${YUMI_FILES_DIR:/tmp/yumi-files}") String filesDir) {
        this.root = Path.of(filesDir);
    }

    @Override
    public Health health() {
        try {
            Files.createDirectories(root);
            Path probe = Files.createTempFile(root, ".health-", ".probe");
            Files.deleteIfExists(probe);
            return Health.up().withDetail("directory", root.toString()).build();
        } catch (Exception error) {
            return Health.down().withDetail("directory", root.toString())
                    .withDetail("error", error.getClass().getSimpleName()).build();
        }
    }
}

package com.yumi.platform.api.http;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 未认证健康检查：只暴露服务与数据库是否可用，供容器编排和运维探针使用。
 * 不返回异常详情、账号或业务数据。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final JdbcClient jdbc;

    public HealthController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record HealthView(String status, String database) {
    }

    @GetMapping
    public ResponseEntity<HealthView> health() {
        try {
            jdbc.sql("SELECT 1").query(Integer.class).single();
            return ResponseEntity.ok(new HealthView("UP", "UP"));
        } catch (RuntimeException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new HealthView("DOWN", "DOWN"));
        }
    }
}

package com.yumi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 1.10 数据库不可用时就绪失败（platform-foundation“数据库不可用时未就绪”）：
 * 指向无监听端口的库、关闭 Flyway/JPA 结构校验后，readiness 含 db 组应 DOWN，liveness 仍 UP。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3399/yumi_nonexistent?connectTimeout=2000&socketTimeout=2000&serverTimezone=UTC",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
})
class ActuatorReadinessDbDownTest {

    @Autowired
    HealthEndpoint healthEndpoint;

    @Test
    void readinessDownWhenDatabaseUnavailable() {
        var readiness = healthEndpoint.healthForPath("readiness");
        assertThat(readiness).isNotNull();
        assertThat(readiness.getStatus().getCode()).isEqualTo("DOWN");
    }

    @Test
    void livenessStaysUpWhenDatabaseUnavailable() {
        var liveness = healthEndpoint.healthForPath("liveness");
        assertThat(liveness).isNotNull();
        assertThat(liveness.getStatus().getCode()).isEqualTo("UP");
    }
}

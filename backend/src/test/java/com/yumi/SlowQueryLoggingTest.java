package com.yumi;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务 9.5（慢查询）：JPA/Hibernate 慢查询日志已生效——阈值设为 100ms 时，
 * 执行 300ms 的语句必须在日志中留下 SQL 痕迹（阈值与 SQL 文本只出现在该日志行，可作判据）。
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = "spring.jpa.properties.hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS=100")
class SlowQueryLoggingTest {

    @Autowired
    EntityManager entityManager;

    @Test
    void logsStatementsSlowerThanThreshold(CapturedOutput output) {
        entityManager.createNativeQuery("SELECT SLEEP(0.3)").getSingleResult();

        var text = output.toString();
        assertThat(text)
                .as("慢查询日志必须包含超阈值语句的 SQL 文本")
                .contains("SLEEP(0.3)");
    }
}

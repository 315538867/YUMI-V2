package com.yumi;

import static org.assertj.core.api.Assertions.assertThat;

import com.yumi.support.AbstractMySqlIntegrationTest;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 工程骨架的容器迁移 smoke：独立 MySQL 容器可启动、Flyway 可执行、JdbcClient 可查询。
 *
 * <p>本测试只证明测试基础设施可用，不证明任何业务能力；业务断言在各自模块的集成测试中完成。
 */
class SkeletonMigrationIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    @DisplayName("独立容器 MySQL 可执行 Flyway 并查询系统时间")
    void containerDatabaseIsUsable() {
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        flyway.migrate();

        String version = jdbcClient.sql("SELECT VERSION()").query(String.class).single();
        assertThat(version).startsWith("8.4");

        String database = jdbcClient.sql("SELECT DATABASE()").query(String.class).single();
        assertThat(database).isEqualTo("yumi_it");
    }
}

package com.yumi.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 真实 MySQL 集成测试基类（本地实例）。
 *
 * <p>按用户要求连接本地 MySQL（root），但始终使用**独立 schema** {@code yumi_it}：Flyway 在该
 * schema 内建表，测试只清空自己 schema 的表，不触碰本机其他库的数据。
 *
 * <p>连接参数可用环境变量覆盖：{@code YUMI_DB_URL}、{@code YUMI_DB_USER}、{@code YUMI_DB_PASSWORD}；
 * 缺省为本机 3306、root、空密码、schema {@code yumi_it}。
 *
 * <p>MySQL 不可用时测试必须明确失败，不得退化为内存数据库。
 */
@SpringBootTest
@ActiveProfiles("it")
public abstract class AbstractMySqlIntegrationTest {

    private static String jdbcUrl() {
        return env("YUMI_DB_URL", DEFAULT_URL);
    }

    private static String jdbcUser() {
        return env("YUMI_DB_USER", "root");
    }

    private static String jdbcPassword() {
        return env("YUMI_DB_PASSWORD", "");
    }

    private static final String DEFAULT_URL =
            "jdbc:mysql://127.0.0.1:3306/yumi_it?useUnicode=true&characterEncoding=utf8"
                    + "&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false"
                    + "&createDatabaseIfNotExist=true";

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> env("YUMI_DB_URL", DEFAULT_URL));
        registry.add("spring.datasource.username", () -> env("YUMI_DB_USER", "root"));
        registry.add("spring.datasource.password", () -> env("YUMI_DB_PASSWORD", ""));
        // 基线要求 READ-COMMITTED：本地 MySQL 默认 REPEATABLE-READ 会改变 FOR UPDATE 取锁行为，
        // 必须显式固定，保证本地与容器环境语义一致。
        registry.add("spring.datasource.hikari.transaction-isolation",
                () -> "TRANSACTION_READ_COMMITTED");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "16");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "10000");
    }

    /**
     * 恢复种子引用数据的基线版本。
     *
     * <p>本地库是持久化的：种子行的 {@code row_version} 会随每次运行递增，导致按固定版本号
     * 编写的用例在第二次运行时失败。这里在每个集成测试前把种子行复位到版本 1。
     */
    @org.junit.jupiter.api.BeforeEach
    void restoreSeedBaseline() {
        try (var connection = java.sql.DriverManager.getConnection(
                jdbcUrl(), jdbcUser(), jdbcPassword());
                var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE product_entry_defaults SET glue_loss_rate = 0.05000000,
                        glue_unit_price = 0.1000, color_paste_unit_price = 0.2000, row_version = 1
                    """);
            statement.executeUpdate("UPDATE product_making_star_options SET row_version = 1");
            statement.executeUpdate("UPDATE product_process_time_options SET row_version = 1");
            statement.executeUpdate("""
                    UPDATE workforce_team_efficiency_standards SET content_version = 1, row_version = 1
                    """);
            statement.executeUpdate("""
                    UPDATE workforce_daily_work_time_standard SET content_version = 1, row_version = 1
                    """);
            statement.executeUpdate("UPDATE production_defect_reasons SET status = 'ACTIVE', row_version = 1");
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("恢复种子基线失败", exception);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null) {
            value = System.getProperty(name.toLowerCase().replace('_', '.'));
        }
        return value == null || value.isBlank() ? fallback : value;
    }
}

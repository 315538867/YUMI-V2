package com.yumi;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class FlywayFoundationMigrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    DataSource dataSource;

    @Test
    void migratesEmptyDatabaseAndCreatesFoundationTables() {
        assertThat(tableExists("flyway_schema_history")).isTrue();
        assertThat(tableExists("admin_accounts")).isTrue();
        assertThat(tableExists("number_sequences")).isTrue();
        assertThat(tableExists("file_metadata")).isTrue();
        assertThat(columnExists("admin_accounts", "username")).isTrue();
        assertThat(columnExists("admin_accounts", "status")).isTrue();
        assertThat(columnExists("admin_accounts", "created_at")).isTrue();
        assertThat(columnExists("admin_accounts", "request_id")).isTrue();
        assertThat(columnExists("admin_accounts", "idempotency_key")).isTrue();
        assertThat(columnType("admin_accounts", "id")).isEqualTo("bigint unsigned");
        assertThat(columnType("admin_accounts", "created_at")).isEqualTo("datetime(6)");
        assertThat(columnExists("number_sequences", "sequence_key")).isTrue();
        assertThat(columnExists("number_sequences", "current_value")).isTrue();
    }

    @Test
    void recordsExactlyOneSuccessfulFoundationMigrationAfterRepeatedStartup() {
        var appliedMigrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1",
                Integer.class);

        assertThat(appliedMigrations).isEqualTo(1);
    }

    @Test
    void rejectsModifiedAppliedMigration(@TempDir Path directory) throws IOException {
        var original = getClass().getResourceAsStream("/db/migration/V1__foundation.sql");
        assertThat(original).isNotNull();
        var migration = Files.write(directory.resolve("V1__foundation.sql"), original.readAllBytes());
        Files.writeString(migration, "\n-- altered after application\n", java.nio.file.StandardOpenOption.APPEND);

        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:" + directory)
                .load();

        assertThatThrownBy(flyway::validate)
                .isInstanceOf(FlywayValidateException.class)
                .hasMessageContaining("checksum mismatch");
    }

    private boolean tableExists(String tableName) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) > 0 FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                Boolean.class,
                tableName));
    }

    private boolean columnExists(String tableName, String columnName) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) > 0 FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Boolean.class,
                tableName,
                columnName));
    }

    private String columnType(String tableName, String columnName) {
        return jdbcTemplate.queryForObject(
                "SELECT CONCAT(data_type, IF(data_type IN ('bigint', 'int'), IF(is_nullable = 'NO', ' unsigned', ''), ''), "
                        + "IF(data_type IN ('datetime', 'timestamp'), CONCAT('(', datetime_precision, ')'), '')) "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class,
                tableName,
                columnName);
    }
}

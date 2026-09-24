package com.yumi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MySqlInfrastructureTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void startsAgainstConfiguredMySqlAndUsesUtcConnection() {
        var version = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
        var timeZone = jdbcTemplate.queryForObject("SELECT @@session.time_zone", String.class);

        assertThat(version).startsWith("8.");
        assertThat(timeZone).isEqualTo("+00:00");
    }
}

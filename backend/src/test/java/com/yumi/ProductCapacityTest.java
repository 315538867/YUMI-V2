package com.yumi;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段五 5.1 制品产能：products 的模具数量与每日批次数落库、正整数校验、
 * 每日最大产能由服务端派生且不落库。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductCapacityTest {

    private static final String USERNAME = "capacity-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    ObjectMapper objectMapper;

    private Cookie sessionCookie;

    @BeforeEach
    void setup() throws Exception {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO admin_accounts (username, password_hash, status, version, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, USERNAME, passwordEncoder.encode(PASSWORD));
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM products WHERE name LIKE 'TST-产能%'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private String body(String name, String moldQuantity, String dailyBatchLimit) {
        var mold = moldQuantity == null ? "" : "\"moldQuantity\":" + moldQuantity + ",";
        var batch = dailyBatchLimit == null ? "" : "\"dailyBatchLimit\":" + dailyBatchLimit + ",";
        return """
                {"name":"%s","starLevelId":3,"salePrice":"10.0000","weightG":100,
                 %s%s"boxLaborFee":"0.5000","transportPackingFee":"0.3000","moldAmortFee":"0.0000"}
                """.formatted(name, mold, batch);
    }

    @Test
    void migrationDeclaresPositiveCapacityColumns() {
        var columns = jdbcTemplate.queryForList("""
                SELECT column_name, column_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'products'
                  AND column_name IN ('mold_quantity', 'daily_batch_limit')
                """);
        assertThat(columns).hasSize(2);
        for (var column : columns) {
            assertThat(column.get("column_type")).isEqualTo("int unsigned");
            assertThat(column.get("is_nullable")).isEqualTo("NO");
            assertThat(column.get("column_default")).isNull();
        }
        var dailyMaxCapacity = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'products'
                  AND column_name = 'daily_max_capacity'
                """);
        assertThat(dailyMaxCapacity).isEmpty();

        jdbcTemplate.update("""
                INSERT INTO products (product_no, name, status, star_level_id, star_name, star_std_minutes,
                    sale_price, weight_g, mold_quantity, daily_batch_limit, version, created_at, updated_at)
                VALUES ('TCAP99', 'TST-产能正数', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 1, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO products (product_no, name, status, star_level_id, star_name, star_std_minutes,
                    sale_price, weight_g, mold_quantity, daily_batch_limit, version, created_at, updated_at)
                VALUES ('TCAP98', 'TST-产能零值', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_products_mold_quantity");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO products (product_no, name, status, star_level_id, star_name, star_std_minutes,
                    sale_price, weight_g, mold_quantity, daily_batch_limit, version, created_at, updated_at)
                VALUES ('TCAP97', 'TST-批次零值', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 1, 0, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_products_daily_batch_limit");
        jdbcTemplate.update("DELETE FROM products WHERE product_no IN ('TCAP99', 'TCAP98', 'TCAP97')");
    }

    @Test
    void rejectsNonPositiveCapacityInputs() throws Exception {
        mockMvc.perform(post("/api/products")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("TST-产能缺字段", null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='moldQuantity')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='dailyBatchLimit')]").exists());

        for (var invalid : new String[]{"0", "-1"}) {
            mockMvc.perform(post("/api/products")
                            .cookie(sessionCookie)
                            .header("Idempotency-Key", key())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("TST-产能非法" + invalid, invalid, "5")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field=='moldQuantity')]").exists());
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM products WHERE name LIKE 'TST-产能%'", Integer.class)).isZero();
    }

    @Test
    void derivesDailyMaxCapacityFromMoldAndBatch() throws Exception {
        var created = mockMvc.perform(post("/api/products")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("TST-产能商品", "10", "5")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.moldQuantity").value(10))
                .andExpect(jsonPath("$.data.dailyBatchLimit").value(5))
                .andExpect(jsonPath("$.data.dailyMaxCapacity").value(50))
                .andReturn();
        var data = objectMapper.readTree(created.getResponse().getContentAsString()).path("data");
        var id = data.path("id").asLong();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/products/" + id)
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"moldQuantity\":3,\"dailyBatchLimit\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.moldQuantity").value(3))
                .andExpect(jsonPath("$.data.dailyBatchLimit").value(4))
                .andExpect(jsonPath("$.data.dailyMaxCapacity").value(12));

        var stored = jdbcTemplate.queryForMap(
                "SELECT mold_quantity, daily_batch_limit FROM products WHERE id = ?", id);
        assertThat(((Number) stored.get("mold_quantity")).intValue()).isEqualTo(3);
        assertThat(((Number) stored.get("daily_batch_limit")).intValue()).isEqualTo(4);
    }
}

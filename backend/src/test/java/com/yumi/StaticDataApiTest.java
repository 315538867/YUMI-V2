package com.yumi;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 2.22 静态数据 API：类别由系统固定 code（不可增删改名），条目按类别管理。
 * 覆盖类别清单、条目读取、用户自建条目增改删、引用守卫、工种仅改名、未知类别与认证。
 * 2026-09-25 起三类都只提供整数标准分钟，工种不再有启用状态。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StaticDataApiTest {

    private static final String USERNAME = "static-data-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String BASE = "/api/settings/static-data";

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
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
        jdbcTemplate.update("""
                INSERT INTO admin_accounts (
                    username, password_hash, status, version, created_at, updated_at
                ) VALUES (?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, USERNAME, passwordEncoder.encode(PASSWORD));
        jdbcTemplate.update("DELETE FROM seam_types WHERE name LIKE '验收缝边%'");
        jdbcTemplate.update("DELETE FROM star_levels WHERE name LIKE '验收星级%'");
        jdbcTemplate.update("UPDATE work_types SET name = '捏毛装袋' WHERE code = 'PACKING_BAG'");
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void listsFixedCategoriesWithItemCounts() throws Exception {
        mockMvc.perform(get(BASE).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].code").value("STAR_LEVEL"))
                .andExpect(jsonPath("$.data[0].name").value("制品星级"))
                .andExpect(jsonPath("$.data[1].code").value("PACKAGING_TIER"))
                .andExpect(jsonPath("$.data[2].code").value("SEAM_TYPE"))
                .andExpect(jsonPath("$.data[3].code").value("WORK_TYPE"))
                .andExpect(jsonPath("$.data[3].itemCount").value(4));
    }

    @Test
    void readsItemsPerCategoryWithCategorySpecificFields() throws Exception {
        mockMvc.perform(get(BASE + "/STAR_LEVEL").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("一星"))
                .andExpect(jsonPath("$.data[0].stdMinutes").value("5"));

        mockMvc.perform(get(BASE + "/WORK_TYPE").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("MAKING"))
                .andExpect(jsonPath("$.data[0].name").value("制作"))
                .andExpect(jsonPath("$.data[0].stdMinutes").doesNotExist());

        mockMvc.perform(get(BASE + "/UNKNOWN").cookie(sessionCookie))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void userManagedCategorySupportsCreateUpdateDelete() throws Exception {
        var created = mockMvc.perform(post(BASE + "/SEAM_TYPE/items")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content("{\"name\":\"验收缝边-单边\",\"stdMinutes\":\"5\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("验收缝边-单边"))
                .andExpect(jsonPath("$.data.stdMinutes").value("5"))
                .andReturn();
        var id = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();

        // 重名 409
        mockMvc.perform(post(BASE + "/SEAM_TYPE/items")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content("{\"name\":\"验收缝边-单边\",\"stdMinutes\":\"8\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT_DUPLICATE"));

        // 改名与改值
        mockMvc.perform(patch(BASE + "/SEAM_TYPE/items/" + id)
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content("{\"name\":\"验收缝边-双边\",\"stdMinutes\":\"8\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("验收缝边-双边"))
                .andExpect(jsonPath("$.data.stdMinutes").value("8"));

        // 未引用可删
        mockMvc.perform(delete(BASE + "/SEAM_TYPE/items/" + id)
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key()))
                .andExpect(status().isNoContent());

        // 非法值 400
        mockMvc.perform(post(BASE + "/SEAM_TYPE/items")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content("{\"name\":\"验收缝边-非法\",\"stdMinutes\":\"0\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='stdMinutes')]").isNotEmpty());
    }

    @Test
    void referencedItemCannotBeDeleted() throws Exception {
        var created = mockMvc.perform(post(BASE + "/STAR_LEVEL/items")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content("{\"name\":\"验收星级-引用\",\"stdMinutes\":\"45\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        var id = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();

        jdbcTemplate.update("""
                INSERT INTO products (product_no, name, status, star_level_id, star_name, star_std_minutes,
                    sale_price, weight_g, mold_quantity, daily_batch_limit, version, created_at, updated_at)
                VALUES (?, 'TST-静态数据引用', 'ACTIVE', ?, '验收星级-引用', 45, 1.0000, 1, 10, 5, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, "Z" + String.format("%05d", id % 100000), id);
        try {
            mockMvc.perform(delete(BASE + "/STAR_LEVEL/items/" + id)
                            .cookie(sessionCookie)
                            .header("Idempotency-Key", key()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT_REFERENCED"));
            mockMvc.perform(get(BASE + "/STAR_LEVEL").cookie(sessionCookie))
                    .andExpect(jsonPath("$.data[?(@.id == " + id + ")]").exists());
        } finally {
            jdbcTemplate.update("DELETE FROM products WHERE name = 'TST-静态数据引用'");
            jdbcTemplate.update("DELETE FROM star_levels WHERE id = ?", id);
        }
    }

    @Test
    void seamTypeReferencedByProductDefaultCannotBeDeleted() throws Exception {
        var created = mockMvc.perform(post(BASE + "/SEAM_TYPE/items")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content("{\"name\":\"验收缝边-被商品默认引用\",\"stdMinutes\":\"5\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        var id = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();

        jdbcTemplate.update("""
                INSERT INTO products (product_no, name, status, star_level_id, star_name, star_std_minutes,
                    sale_price, weight_g, seam_type_id, seam_type_name, seam_std_minutes, seam_unit_cost,
                    seam_fee, mold_quantity, daily_batch_limit, version, created_at, updated_at)
                VALUES (?, 'TST-缝边默认引用', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    1.0000, 1, ?, '验收缝边-被商品默认引用', 5, 1.2500, 2.0000, 10, 5, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, "Y" + String.format("%05d", id % 100000), id);
        try {
            mockMvc.perform(delete(BASE + "/SEAM_TYPE/items/" + id)
                            .cookie(sessionCookie)
                            .header("Idempotency-Key", key()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT_REFERENCED"));
            mockMvc.perform(get(BASE + "/SEAM_TYPE").cookie(sessionCookie))
                    .andExpect(jsonPath("$.data[?(@.id == " + id + ")]").exists());
        } finally {
            jdbcTemplate.update("DELETE FROM products WHERE name = 'TST-缝边默认引用'");
            jdbcTemplate.update("DELETE FROM seam_types WHERE id = ?", id);
        }
    }

    @Test
    void workTypeOnlyAllowsRename() throws Exception {
        var items = mockMvc.perform(get(BASE + "/WORK_TYPE").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var id = objectMapper.readTree(items).path("data").get(1).path("id").asLong();

        // 新增/删除被拒
        mockMvc.perform(post(BASE + "/WORK_TYPE/items")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content("{\"name\":\"验收工种\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"));
        mockMvc.perform(delete(BASE + "/WORK_TYPE/items/" + id)
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"));

        // 只能改名：不再有启用状态字段
        mockMvc.perform(patch(BASE + "/WORK_TYPE/items/" + id)
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content("{\"name\":\"验收工种-装袋\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("PACKING_BAG"))
                .andExpect(jsonPath("$.data.name").value("验收工种-装袋"))
                .andExpect(jsonPath("$.data.active").doesNotExist());
    }

    @Test
    void categoryListQueryWritesNothing() throws Exception {
        var before = new Counts();

        mockMvc.perform(get(BASE).cookie(sessionCookie)).andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/STAR_LEVEL").cookie(sessionCookie)).andExpect(status().isOk());

        assertThat(before.equals(new Counts())).as("静态数据查询不得产生业务写入").isTrue();
    }

    private String key() {
        return "sd-" + System.nanoTime();
    }

    private final class Counts {

        private final int changeLogs = count("SELECT COUNT(*) FROM master_data_change_logs");
        private final int audits = count("SELECT COUNT(*) FROM audit_logs");
        private final int idempotency = count("SELECT COUNT(*) FROM idempotency_records");

        private int count(String sql) {
            var value = jdbcTemplate.queryForObject(sql, Integer.class);
            return value == null ? 0 : value;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Counts counts)) {
                return false;
            }
            return changeLogs == counts.changeLogs && audits == counts.audits
                    && idempotency == counts.idempotency;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(changeLogs, audits, idempotency);
        }
    }
}

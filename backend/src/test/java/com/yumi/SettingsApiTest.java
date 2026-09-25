package com.yumi;

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
 * /settings 全局设置 API：单价与默认值、星级（用户自建静态条目，2026-09-24 定稿）、包装档位；
 * 被商品引用的星级/档位禁止删除（CONFLICT_REFERENCED）；全局变更不回溯既有商品快照。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SettingsApiTest {

    private static final String USERNAME = "settings-test-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PasswordEncoder passwordEncoder;

    private Cookie sessionCookie;

    @BeforeEach
    void login() throws Exception {
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
        jdbcTemplate.update("""
                INSERT INTO admin_accounts (
                    username, password_hash, status, version, created_at, updated_at
                ) VALUES (?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, USERNAME, passwordEncoder.encode(PASSWORD));
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
        // 恢复全局设置与测试脏数据（星级种子 id1-5：5/10/15/20/30）
        jdbcTemplate.update("UPDATE catalog_settings SET setting_value = 0 WHERE setting_key <> 'loss_rate_default'");
        // 时薪、工作日小时数与制品有效工时率是三类人工费的派生基数，清零会让后续用例的人工费/产量全为 0 → 恢复 SQL 默认值
        jdbcTemplate.update("UPDATE catalog_settings SET setting_value = 15.000000 WHERE setting_key = 'hourly_wage'");
        jdbcTemplate.update("UPDATE catalog_settings SET setting_value = 8.000000 WHERE setting_key = 'workday_hours'");
        jdbcTemplate.update(
                "UPDATE catalog_settings SET setting_value = 0.750000 WHERE setting_key = 'making_effective_hour_rate'");
        jdbcTemplate.update("DELETE FROM packaging_tiers WHERE tier_name LIKE '验收档位%'");
        jdbcTemplate.update("DELETE FROM star_levels WHERE name LIKE '测试星级%' OR name LIKE '被引用星级%'");
        int[] mins = {5, 10, 15, 20, 30};
        for (int i = 0; i < mins.length; i++) {
            jdbcTemplate.update("UPDATE star_levels SET std_minutes = ? WHERE id = ?", mins[i], i + 1);
        }
    }

    private String key() {
        return "set-" + System.nanoTime();
    }

    @Test
    void exposesAllGlobalValuesStarLevelsAndTiers() throws Exception {
        mockMvc.perform(get("/api/settings").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.values.glueUnitPrice").isString())
                .andExpect(jsonPath("$.data.values.lossRateDefault").isString())
                .andExpect(jsonPath("$.data.values.boxLaborDefault").isString())
                .andExpect(jsonPath("$.data.starLevels.length()").value(5))
                .andExpect(jsonPath("$.data.starLevels[0].id").value(1))
                .andExpect(jsonPath("$.data.starLevels[0].name").value("一星"))
                .andExpect(jsonPath("$.data.starLevels[0].stdMinutes").value(5))
                .andExpect(jsonPath("$.data.starLevels[4].stdMinutes").value(30))
                .andExpect(jsonPath("$.data.packagingTiers").isArray());
    }

    @Test
    void patchValuesAndWritesChangeLog() throws Exception {
        mockMvc.perform(patch("/api/settings")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content("{\"glueUnitPrice\":\"0.0123\",\"lossRateDefault\":\"15.5\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.glueUnitPrice").value("0.0123"))
                .andExpect(jsonPath("$.data.lossRateDefault").value("15.500000"));

        var stored = jdbcTemplate.queryForObject(
                "SELECT setting_value FROM catalog_settings WHERE setting_key = 'glue_unit_price'",
                String.class);
        assertThat(stored).startsWith("0.0123");

        var log = jdbcTemplate.queryForMap(
                "SELECT admin_username, request_id FROM master_data_change_logs "
                        + "WHERE entity_type = 'SETTINGS' AND business_no = 'GLOBAL_SETTINGS' ORDER BY id DESC LIMIT 1");
        assertThat(log.get("admin_username")).isEqualTo(USERNAME);
        assertThat(log.get("request_id")).isNotNull();
    }

    @Test
    void overPreciseSettingValuesRoundHalfUpInsteadOfFailing() throws Exception {
        // 列定义为 DECIMAL(19,6)：超过目标精度的小数必须按 HALF_UP 归一，不能抛 ArithmeticException 变成 500
        mockMvc.perform(patch("/api/settings")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content("{\"glueUnitPrice\":\"0.01245\",\"lossRateDefault\":\"15.5000005\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.glueUnitPrice").value("0.0125"))
                .andExpect(jsonPath("$.data.lossRateDefault").value("15.500001"));
    }

    @Test
    void starLevelChangeDoesNotBackfillExistingProductSnapshots() throws Exception {
        var productId = createProduct("设置快照-", 3L);

        // PATCH 修改全局星级时长（15 → 60）
        mockMvc.perform(patch("/api/settings/static-data/STAR_LEVEL/items/3")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content("{\"stdMinutes\":60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stdMinutes").value("60"));

        // 既有商品快照不变
        assertStarSnapshot(productId, 15, 24, "5.0000");

        // 关键反溯断言：全局变更后普通编辑（未重选星级）也不得回溯快照
        var storedVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM products WHERE id = ?", Integer.class, productId);
        mockMvc.perform(patch("/api/products/" + productId)
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content("{\"version\":" + storedVersion + ",\"note\":\"全局变更后的普通编辑\"}"))
                .andExpect(status().isOk());
        assertStarSnapshot(productId, 15, 24, "5.0000");

        // 新建商品按新全局值
        mockMvc.perform(post("/api/products")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content("""
                                {"name":"设置快照新-%d","starLevelId":3,"salePrice":"10.0000","weightG":100,
                                 "lossRatePercent":"0","seamMinutes":"0","seamDefaultFee":"0"}
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.starStdMinutes").value(60))
                .andExpect(jsonPath("$.data.qty6h").value(6));

        // 还原全局值
        mockMvc.perform(patch("/api/settings/static-data/STAR_LEVEL/items/3")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content("{\"stdMinutes\":15}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stdMinutes").value("15"));
    }

    @Test
    void packagingTierLifecycleSnapshotAndReferencedGuard() throws Exception {
        var tierName = "验收档位-" + System.nanoTime();
        var created = mockMvc.perform(post("/api/settings/static-data/PACKAGING_TIER/items")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content("""
                                {"name":"%s","stdMinutes":"8"}
                                """.formatted(tierName)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value(tierName))
                .andReturn();
        var tierId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();

        // 重名 409
        mockMvc.perform(post("/api/settings/static-data/PACKAGING_TIER/items")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content("""
                                {"name":"%s","stdMinutes":"1"}
                                """.formatted(tierName)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT_DUPLICATE"));

        var productId = createProductWithTier("档位快照-", tierId);

        // 档位全局修改 → 既有商品快照不回溯
        mockMvc.perform(patch("/api/settings/static-data/PACKAGING_TIER/items/" + tierId)
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content("""
                                {"name":"%s","stdMinutes":"9"}
                                """.formatted(tierName)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/products/" + productId).cookie(sessionCookie))
                .andExpect(jsonPath("$.data.packagingTierName").value(tierName))
                .andExpect(jsonPath("$.data.packagingStdMinutes").value(8))
                .andExpect(jsonPath("$.data.packagingLaborFee").value("2.3000"));

        // 被引用 → 禁止删除
        mockMvc.perform(delete("/api/settings/static-data/PACKAGING_TIER/items/" + tierId)
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT_REFERENCED"));
        mockMvc.perform(get("/api/settings/static-data/PACKAGING_TIER").cookie(sessionCookie))
                .andExpect(jsonPath("$.data[?(@.id == " + tierId + ")]").exists());

        // 未被引用的临时档位可删
        var tmpName = "验收档位-临时-" + System.nanoTime();
        var tmp = mockMvc.perform(post("/api/settings/static-data/PACKAGING_TIER/items")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content("""
                                {"name":"%s","stdMinutes":"2"}
                                """.formatted(tmpName)))
                .andExpect(status().isCreated())
                .andReturn();
        var tmpId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(tmp.getResponse().getContentAsString()).path("data").path("id").asLong();
        mockMvc.perform(delete("/api/settings/static-data/PACKAGING_TIER/items/" + tmpId)
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/settings/static-data/PACKAGING_TIER").cookie(sessionCookie))
                .andExpect(jsonPath("$.data[?(@.id == " + tmpId + ")]").doesNotExist());
    }

    @Test
    void validationAndAuthGuards() throws Exception {
        mockMvc.perform(get("/api/settings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(patch("/api/settings")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"glueUnitPrice\":\"0.1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("Idempotency-Key"));

        mockMvc.perform(patch("/api/settings")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content("{\"unknownSetting\":\"1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"));

        mockMvc.perform(patch("/api/settings/static-data/STAR_LEVEL/items/3")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content("{\"stdMinutes\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"));

        mockMvc.perform(patch("/api/settings/static-data/STAR_LEVEL/items/999999")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content("{\"stdMinutes\":10}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(delete("/api/settings/static-data/PACKAGING_TIER/items/999999")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private long createProduct(String prefix, long starLevelId) throws Exception {
        var result = mockMvc.perform(post("/api/products")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content("""
                                {"name":"%s%d","starLevelId":%d,"salePrice":"10.0000","weightG":100,
                                 "lossRatePercent":"0","seamMinutes":"0","seamDefaultFee":"0"}
                                """.formatted(prefix, System.nanoTime(), starLevelId)))
                .andExpect(status().isCreated())
                .andReturn();
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private long createProductWithTier(String prefix, long tierId) throws Exception {
        var result = mockMvc.perform(post("/api/products")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content("""
                                {"name":"%s%d","starLevelId":1,"salePrice":"10.0000","weightG":100,
                                 "lossRatePercent":"0",
                                 "packagingTierId":%d,"packagingCommission":"0.3000"}
                                """.formatted(prefix, System.nanoTime(), tierId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.packagingLaborFee").value("2.3000"))
                .andReturn();
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private void assertStarSnapshot(long productId, int stdMinutes, int qty6, String laborFee) throws Exception {
        mockMvc.perform(get("/api/products/" + productId).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.starStdMinutes").value(stdMinutes))
                .andExpect(jsonPath("$.data.qty6h").value(qty6))
                .andExpect(jsonPath("$.data.productLaborFee").value(laborFee));
    }
}

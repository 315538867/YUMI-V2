package com.yumi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 2.15 商品计算输入解析矩阵：新建与编辑共用同一解析入口后必须保持的引用快照与合并语义。
 * 覆盖同引用不刷新、换引用取当前全局、全局变更不回溯其他商品、版本冲突无写入、非法计算字段聚合 fieldErrors。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductSnapshotMatrixTest {

    private static final String USERNAME = "snapshot-matrix-admin";
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
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
        jdbcTemplate.update("""
                INSERT INTO admin_accounts (
                    username, password_hash, status, version, created_at, updated_at
                ) VALUES (?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, USERNAME, passwordEncoder.encode(PASSWORD));
        setSetting("glue_unit_price", "0.010000");
        setSetting("colorpaste_unit_price", "0.020000");
        setSetting("loss_rate_default", "20.000000");
        setSetting("box_labor_default", "0.500000");
        setSetting("transport_packing_default", "0.300000");
        setSetting("sundries_default", "0.200000");
        setSetting("rent_utilities_default", "0.400000");
        // 基线固定为 0：本类断言基于“未填提成时取全局默认”，不依赖库内残留值
        setSetting("packaging_commission_default", "0.000000");
        jdbcTemplate.update("DELETE FROM products WHERE name LIKE 'TST-%'");
        jdbcTemplate.update("DELETE FROM packaging_tiers WHERE tier_name LIKE 'TST-档%'");
        int[] mins = {5, 10, 15, 20, 30};
        for (int i = 0; i < mins.length; i++) {
            jdbcTemplate.update("UPDATE star_levels SET std_minutes = ? WHERE id = ?", mins[i], i + 1);
        }
        sessionCookie = login();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM products WHERE name LIKE 'TST-%'");
        jdbcTemplate.update("DELETE FROM packaging_tiers WHERE tier_name LIKE 'TST-档%'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
        int[] mins = {5, 10, 15, 20, 30};
        for (int i = 0; i < mins.length; i++) {
            jdbcTemplate.update("UPDATE star_levels SET std_minutes = ? WHERE id = ?", mins[i], i + 1);
        }
        for (var key : new String[]{"glue_unit_price", "colorpaste_unit_price",
                "box_labor_default", "transport_packing_default", "sundries_default", "rent_utilities_default"}) {
            setSetting(key, "0.000000");
        }
    }

    @Test
    void sameStarReferenceKeepsSnapshotEvenIfGlobalChanged() throws Exception {
        var created = create(createBody("TST-同星", "25.0000", null));
        starStdMinutes(3, 60);

        // 显式传入与存量相同的星级 id 不等同于刷新：仍用商品自身快照
        var data = patchProduct(created.path("id").asLong(), 0, "{\"version\":%d,\"starLevelId\":3}");

        assertThat(data.path("starStdMinutes").asInt()).isEqualTo(15);
        assertThat(data.path("qty6h").asInt()).isEqualTo(24);
        assertThat(data.path("productLaborFee").asText()).isEqualTo("5.0000");
        assertThat(data.path("totalCost").asText()).isEqualTo("16.2200");
    }

    @Test
    void differentStarReferenceReadsCurrentGlobal() throws Exception {
        var created = create(createBody("TST-换星", "25.0000", null));
        starStdMinutes(1, 12);

        var data = patchProduct(created.path("id").asLong(), 0, "{\"version\":%d,\"starLevelId\":1}");

        // 换成不同引用：取当前全局 12 分钟 → floor(360/12)=30 → 120/30=4.0000
        assertThat(data.path("starName").asText()).isEqualTo("一星");
        assertThat(data.path("starStdMinutes").asInt()).isEqualTo(12);
        assertThat(data.path("qty8h").asInt()).isEqualTo(40);
        assertThat(data.path("qty6h").asInt()).isEqualTo(30);
        assertThat(data.path("productLaborFee").asText()).isEqualTo("4.0000");
    }

    @Test
    void sameTierReferenceKeepsSnapshotEvenIfGlobalChanged() throws Exception {
        long tierId = createTier("TST-档A", "10.000");
        var created = create(createBody("TST-同档", "25.0000", tierId));
        assertThat(created.path("packagingLaborFee").asText()).isEqualTo("2.5000");

        jdbcTemplate.update("UPDATE packaging_tiers SET std_minutes = 20.000 WHERE id = ?", tierId);

        var data = patchProduct(created.path("id").asLong(), 0, "{\"version\":%d,\"packagingTierId\":" + tierId + "}");

        assertThat(data.path("packagingStdMinutes").asInt()).isEqualTo(10);
        assertThat(data.path("packagingLaborFee").asText()).isEqualTo("2.5000");
        assertThat(data.path("totalCost").asText()).isEqualTo("18.7200");
    }

    @Test
    void differentTierReferenceReadsCurrentGlobal() throws Exception {
        long tierA = createTier("TST-档A", "10.000");
        long tierB = createTier("TST-档B", "20.000");
        var created = create(createBody("TST-换档", "25.0000", tierA));

        var data = patchProduct(created.path("id").asLong(), 0, "{\"version\":%d,\"packagingTierId\":" + tierB + "}");

        // 20×0.25 = 5.0000；total = 9.7200 + (5.0000+5.0000+0.5) + 1.0000
        assertThat(data.path("packagingTierId").asLong()).isEqualTo(tierB);
        assertThat(data.path("packagingLaborFee").asText()).isEqualTo("5.0000");
        assertThat(data.path("totalCost").asText()).isEqualTo("21.2200");
    }

    @Test
    void globalChangeDoesNotRewriteOtherProductSnapshots() throws Exception {
        create(createBody("TST-甲", "25.0000", null));
        var second = create(createBody("TST-乙", "25.0000", null));

        starStdMinutes(3, 60);
        setSetting("glue_unit_price", "0.020000");

        var detail = getDetail(second.path("id").asLong());
        assertThat(detail.path("starStdMinutes").asInt()).isEqualTo(15);
        assertThat(detail.path("productLaborFee").asText()).isEqualTo("5.0000");
        assertThat(detail.path("glueUnitPrice").asText()).isEqualTo("0.0100");
        assertThat(detail.path("totalCost").asText()).isEqualTo("16.2200");
    }

    @Test
    void staleVersionConflictLeavesNoWrites() throws Exception {
        var created = create(createBody("TST-冲突", "25.0000", null));
        long id = created.path("id").asLong();
        int logsBefore = changeLogCount(id);

        mockMvc.perform(patch("/api/products/" + id)
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":7,\"salePrice\":\"99.0000\",\"reason\":\"并发冲突\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT_VERSION"));

        var detail = getDetail(id);
        assertThat(detail.path("salePrice").asText()).isEqualTo("25.0000");
        assertThat(detail.path("version").asLong()).isZero();
        assertThat(changeLogCount(id)).isEqualTo(logsBefore);
    }

    @Test
    void explicitRefreshAdoptsCurrentGlobalStarTierAndMaterialPrices() throws Exception {
        long tierId = createTier("TST-档A", "10.000");
        var created = create(createBody("TST-刷新全局", "25.0000", tierId));
        long id = created.path("id").asLong();
        assertThat(created.path("totalCost").asText()).isEqualTo("18.7200");

        // 全局值变化：星级 15→60、档位 10min/0.2→20min/0.5、胶水 0.01→0.02
        starStdMinutes(3, 60);
        jdbcTemplate.update("UPDATE packaging_tiers SET std_minutes = 20.000 WHERE id = ?", tierId);
        setSetting("glue_unit_price", "0.020000");

        // 未请求刷新：星级/档位/料价全部沿用商品快照
        var kept = patchProduct(id, 0, "{\"version\":%d,\"note\":\"不刷新\"}");
        assertThat(kept.path("starStdMinutes").asInt()).isEqualTo(15);
        assertThat(kept.path("glueUnitPrice").asText()).isEqualTo("0.0100");
        assertThat(kept.path("packagingStdMinutes").asInt()).isEqualTo(10);
        assertThat(kept.path("packagingLaborFee").asText()).isEqualTo("2.5000");
        assertThat(kept.path("totalCost").asText()).isEqualTo("18.7200");

        // 显式请求刷新：星级/档位/材料单价全部按当前全局重算
        var refreshed = patchProduct(id, 1, "{\"version\":%d,\"refreshGlobalReferences\":true}");
        assertThat(refreshed.path("starStdMinutes").asInt()).isEqualTo(60);
        assertThat(refreshed.path("qty8h").asInt()).isEqualTo(8);
        assertThat(refreshed.path("qty6h").asInt()).isEqualTo(6);
        assertThat(refreshed.path("productLaborFee").asText()).isEqualTo("20.0000");
        assertThat(refreshed.path("glueUnitPrice").asText()).isEqualTo("0.0200");
        assertThat(refreshed.path("packagingStdMinutes").asInt()).isEqualTo(20);
        assertThat(refreshed.path("packagingLaborFee").asText()).isEqualTo("5.0000");
        // material=12.9600、labor=25.5000、other=1.0000
        assertThat(refreshed.path("totalCost").asText()).isEqualTo("39.4600");
    }

    @Test
    void packagingCommissionDefaultsFromGlobalAndCanBeOverridden() throws Exception {
        setSetting("packaging_commission_default", "0.5000");
        long tierId = createTier("TST-档C", "6.000");
        try {
            // 商品未填提成 → 取全局默认 0.5：6×0.25+0.5 = 2.0000
            var defaulted = create(createBody("TST-提成默认", "25.0000", tierId));
            assertThat(defaulted.path("packagingCommission").asText()).isEqualTo("0.5000");
            assertThat(defaulted.path("packagingLaborFee").asText()).isEqualTo("2.0000");

            // 商品显式覆盖 → 0.8：6×0.25+0.8 = 2.3000
            var overridden = create("""
                    {"name":"TST-提成覆盖","starLevelId":3,"moldQuantity":10,"dailyBatchLimit":5,"salePrice":"25.0000","weightG":270,
                     "lossRatePercent":"20","packagingTierId":%d,"packagingCommission":"0.8000",
                     "boxLaborFee":"0.5","transportPackingFee":"0.3","dailySundriesFee":"0.2",
                     "rentUtilitiesFee":"0.4","moldAmortFee":"0.1"}
                    """.formatted(tierId));
            assertThat(overridden.path("packagingCommission").asText()).isEqualTo("0.8000");
            assertThat(overridden.path("packagingLaborFee").asText()).isEqualTo("2.3000");
        } finally {
            setSetting("packaging_commission_default", "0.000000");
        }
    }

    @Test
    void invalidCalculationFieldsAggregateFieldErrors() throws Exception {
        var created = create(createBody("TST-非法", "25.0000", null));
        long id = created.path("id").asLong();

        mockMvc.perform(patch("/api/products/" + id)
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"salePrice\":\"-1\",\"weightG\":-5,\"starLevelId\":9999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='salePrice')]").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='weightG')]").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='starLevelId')]").isNotEmpty());
    }

    private String createBody(String name, String salePrice, Long tierId) {
        var tier = tierId == null ? "" : ",\"packagingTierId\":" + tierId;
        return """
                {"name":"%s","starLevelId":3,"moldQuantity":10,"dailyBatchLimit":5,"salePrice":"%s","weightG":270,"lossRatePercent":"20",
                 "seamMinutes":"5","seamDefaultFee":"0"%s,
                 "boxLaborFee":"0.5","transportPackingFee":"0.3","dailySundriesFee":"0.2",
                 "rentUtilitiesFee":"0.4","moldAmortFee":"0.1"}
                """.formatted(name, salePrice, tier);
    }

    private void setSetting(String key, String value) {
        jdbcTemplate.update("UPDATE catalog_settings SET setting_value = ? WHERE setting_key = ?",
                new java.math.BigDecimal(value), key);
    }

    private void starStdMinutes(long id, int minutes) {
        jdbcTemplate.update("UPDATE star_levels SET std_minutes = ? WHERE id = ?", minutes, id);
    }

    private long createTier(String name, String stdMinutes) {
        jdbcTemplate.update("""
                INSERT INTO packaging_tiers (tier_name, std_minutes, version, created_at, updated_at)
                VALUES (?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, name, new java.math.BigDecimal(stdMinutes).intValueExact());
        return jdbcTemplate.queryForObject("SELECT id FROM packaging_tiers WHERE tier_name = ?", Long.class, name);
    }

    private int changeLogCount(long productId) {
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_data_change_logs WHERE entity_type = 'PRODUCT' AND entity_id = ?",
                Integer.class, productId);
        return count == null ? 0 : count;
    }

    private Cookie login() throws Exception {
        var result = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getCookie("YUMI_SESSION");
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private JsonNode create(String body) throws Exception {
        var response = mockMvc.perform(post("/api/products")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data");
    }

    private JsonNode getDetail(long id) throws Exception {
        var response = mockMvc.perform(get("/api/products/" + id).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data");
    }

    private JsonNode patchProduct(long id, long version, String body) throws Exception {
        var response = mockMvc.perform(patch("/api/products/" + id)
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(version)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data");
    }
}

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 2.16 商品只读试算 API：POST /api/products/preview 与 POST /api/products/{id}/preview。
 * 覆盖统一信封与完整分项、无业务写入、编辑快照合并与版本、错误码、精确路径豁免、试算与保存一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductPreviewApiTest {

    private static final String USERNAME = "preview-api-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";

    /** 钉死算例：weight=270、loss=20%、三星(std15)、seam=5、sale=25、无包装档位。 */
    private static final String PREVIEW_BODY = """
            {"starLevelId":3,"moldQuantity":10,"dailyBatchLimit":5,"salePrice":"25.0000","weightG":270,"lossRatePercent":"20",
             "seamMinutes":"5","seamDefaultFee":"0",
             "boxLaborFee":"0.5","transportPackingFee":"0.3","dailySundriesFee":"0.2",
             "rentUtilitiesFee":"0.4","moldAmortFee":"0.1"}
            """;

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
        jdbcTemplate.update("DELETE FROM products WHERE name LIKE 'TST-%'");
        int[] mins = {5, 10, 15, 20, 30};
        for (int i = 0; i < mins.length; i++) {
            jdbcTemplate.update("UPDATE star_levels SET std_minutes = ? WHERE id = ?", mins[i], i + 1);
        }
        sessionCookie = login();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM products WHERE name LIKE 'TST-%'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
        for (var key : new String[]{"glue_unit_price", "colorpaste_unit_price",
                "box_labor_default", "transport_packing_default", "sundries_default", "rent_utilities_default"}) {
            setSetting(key, "0.000000");
        }
    }

    @Test
    void createPreviewReturnsFullBreakdownWithoutBusinessWrites() throws Exception {
        var before = new Counts();

        mockMvc.perform(post("/api/products/preview")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PREVIEW_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value(""))
                .andExpect(jsonPath("$.data.glueGrams").value(324))
                .andExpect(jsonPath("$.data.glueCost").value("3.2400"))
                .andExpect(jsonPath("$.data.colorpasteCost").value("6.4800"))
                .andExpect(jsonPath("$.data.materialCost").value("9.7200"))
                .andExpect(jsonPath("$.data.productLaborFee").value("5.0000"))
                .andExpect(jsonPath("$.data.packagingLaborFee").value("0.0000"))
                .andExpect(jsonPath("$.data.boxLaborFee").value("0.5000"))
                .andExpect(jsonPath("$.data.laborCost").value("5.5000"))
                .andExpect(jsonPath("$.data.otherCost").value("1.0000"))
                .andExpect(jsonPath("$.data.totalCost").value("16.2200"))
                .andExpect(jsonPath("$.data.referencePrice").value("23.1714"))
                .andExpect(jsonPath("$.data.qty8h").value(32))
                .andExpect(jsonPath("$.data.qty6h").value(24))
                .andExpect(jsonPath("$.data.salePrice").value("25.0000"))
                .andExpect(jsonPath("$.data.estimatedProfit").value("8.7800"))
                .andExpect(jsonPath("$.data.estimatedMarginRate").value("0.351200"))
                .andExpect(jsonPath("$.data.estimatedMarginRatePercent").value("35.12%"));

        assertThat(before.equals(new Counts()))
                .as("只读试算不得新增商品、编号、变更日志、写审计或幂等记录")
                .isTrue();
    }

    @Test
    void editPreviewMergesSnapshotAndEnforcesVersion() throws Exception {
        var created = create("TST-试算编辑");
        long id = created.path("id").asLong();
        assertThat(created.path("totalCost").asText()).isEqualTo("16.2200");

        // 缺省合并：只改重量 300，星级/档位/单价沿用商品快照 → 360 克、total 17.3000
        var data = previewEdit(id, "{\"version\":%d,\"weightG\":300}");
        assertThat(data.path("glueGrams").asInt()).isEqualTo(360);
        assertThat(data.path("materialCost").asText()).isEqualTo("10.8000");
        assertThat(data.path("totalCost").asText()).isEqualTo("17.3000");
        assertThat(data.path("referencePrice").asText()).isEqualTo("24.7143");
        assertThat(data.path("estimatedMarginRatePercent").asText()).isEqualTo("30.8%");

        // 编辑试算必须带版本号，过期版本返回冲突
        mockMvc.perform(post("/api/products/" + id + "/preview")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weightG\":300}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='version')]").isNotEmpty());

        mockMvc.perform(post("/api/products/" + id + "/preview")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":7,\"weightG\":300}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT_VERSION"));
    }

    @Test
    void previewRejectsInvalidFieldsAndUnknownReferences() throws Exception {
        mockMvc.perform(post("/api/products/preview")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"starLevelId\":9999,\"salePrice\":\"-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='starLevelId')]").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='salePrice')]").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='weightG')]").isNotEmpty());
    }

    @Test
    void previewRequiresAuthenticationAndExistingProduct() throws Exception {
        mockMvc.perform(post("/api/products/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PREVIEW_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(post("/api/products/999999/preview")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void previewExemptionMatchesExactPathsOnly() throws Exception {
        // 精确路径豁免：不带 Idempotency-Key 也能试算
        mockMvc.perform(post("/api/products/preview")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PREVIEW_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        // 相似路径不豁免：仍要求 Idempotency-Key
        mockMvc.perform(post("/api/products/preview/")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PREVIEW_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='Idempotency-Key')]").isNotEmpty());

        mockMvc.perform(post("/api/products/previewx")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PREVIEW_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='Idempotency-Key')]").isNotEmpty());

        // 其他商品写命令规则不变
        var created = create("TST-豁免");
        mockMvc.perform(post("/api/products/" + created.path("id").asLong() + "/disable")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='Idempotency-Key')]").isNotEmpty());
    }

    @Test
    void previewMatchesSavedSnapshotAndSaveRecomputesWithCurrentConfig() throws Exception {
        // 同一输入与同一配置：编辑试算逐项等于保存后的商品快照
        var created = create("TST-一致");
        long id = created.path("id").asLong();
        var preview = previewEdit(id, "{\"version\":%d}");
        var detail = getDetail(id);
        for (var field : new String[]{"glueGrams", "glueCost", "colorpasteCost", "materialCost",
                "productLaborFee", "packagingLaborFee", "laborCost", "otherCost", "totalCost", "referencePrice", "salePrice", "estimatedProfit", "estimatedMarginRate"}) {
            assertThat(preview.path(field).asText())
                    .as("试算与保存快照字段一致：" + field)
                    .isEqualTo(detail.path(field).asText());
        }

        // 配置改变后保存以事务内重算为准：试算 16.2200 不再权威，显式刷新料价后保存得到 19.4600
        setSetting("glue_unit_price", "0.020000");
        var saved = patchWithKey(id, 0,
                "{\"version\":%d,\"refreshMaterialPrices\":true,\"note\":\"改配置后保存\"}");
        assertThat(saved.path("glueUnitPrice").asText()).isEqualTo("0.0200");
        assertThat(saved.path("totalCost").asText()).isEqualTo("19.4600");
        assertThat(preview.path("totalCost").asText()).isEqualTo("16.2200");
    }

    @Test
    void editPreviewHonorsExplicitGlobalRefreshWithoutWriting() throws Exception {
        var created = create("TST-刷新试算");
        long id = created.path("id").asLong();
        assertThat(created.path("totalCost").asText()).isEqualTo("16.2200");

        // 全局变化：三星 15→60 分钟、胶水单价 0.01→0.02
        jdbcTemplate.update("UPDATE star_levels SET std_minutes = 60 WHERE id = 3");
        setSetting("glue_unit_price", "0.020000");
        var before = new Counts();

        // 未请求刷新：星级与材料单价继续用商品快照
        var kept = previewEdit(id, "{\"version\":%d}");
        assertThat(kept.path("productLaborFee").asText()).isEqualTo("5.0000");
        assertThat(kept.path("materialCost").asText()).isEqualTo("9.7200");
        assertThat(kept.path("totalCost").asText()).isEqualTo("16.2200");

        // 显式刷新：星级时长与材料单价按当前全局重算
        var refreshed = previewEdit(id, "{\"version\":%d,\"refreshGlobalReferences\":true}");
        assertThat(refreshed.path("qty8h").asInt()).isEqualTo(8);
        assertThat(refreshed.path("qty6h").asInt()).isEqualTo(6);
        assertThat(refreshed.path("productLaborFee").asText()).isEqualTo("20.0000");
        assertThat(refreshed.path("materialCost").asText()).isEqualTo("12.9600");
        // material=12.9600、labor=20.5000、other=1.0000
        assertThat(refreshed.path("totalCost").asText()).isEqualTo("34.4600");

        assertThat(before.equals(new Counts())).as("显式刷新试算同样不得写业务数据").isTrue();
    }

    @Test
    void clientDerivedAmountsAreIgnored() throws Exception {
        var body = PREVIEW_BODY.replace("{\"starLevelId\"",
                "{\"totalCost\":\"0.0001\",\"estimatedProfit\":\"9999.0000\",\"referencePrice\":\"0.0001\","
                        + "\"glueGrams\":1,\"starLevelId\"");

        var data = previewCreate(body);

        assertThat(data.path("glueGrams").asInt()).isEqualTo(324);
        assertThat(data.path("totalCost").asText()).isEqualTo("16.2200");
        assertThat(data.path("referencePrice").asText()).isEqualTo("23.1714");
        assertThat(data.path("estimatedProfit").asText()).isEqualTo("8.7800");
    }

    private JsonNode previewCreate(String body) throws Exception {
        var response = mockMvc.perform(post("/api/products/preview")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data");
    }

    private JsonNode previewEdit(long id, String body) throws Exception {
        var response = mockMvc.perform(post("/api/products/" + id + "/preview")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data");
    }

    private JsonNode create(String name) throws Exception {
        var body = """
                {"name":"%s","starLevelId":3,"moldQuantity":10,"dailyBatchLimit":5,"salePrice":"25.0000","weightG":270,"lossRatePercent":"20",
                 "seamMinutes":"5","seamDefaultFee":"0",
                 "boxLaborFee":"0.5","transportPackingFee":"0.3","dailySundriesFee":"0.2",
                 "rentUtilitiesFee":"0.4","moldAmortFee":"0.1"}
                """.formatted(name);
        var response = mockMvc.perform(post("/api/products")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data");
    }

    private JsonNode patchWithKey(long id, long version, String body) throws Exception {
        var response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/products/" + id)
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(version)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data");
    }

    private JsonNode getDetail(long id) throws Exception {
        var response = mockMvc.perform(get("/api/products/" + id).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data");
    }

    private void setSetting(String key, String value) {
        jdbcTemplate.update("UPDATE catalog_settings SET setting_value = ? WHERE setting_key = ?",
                new java.math.BigDecimal(value), key);
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

    /** 试算前后的业务写入计数快照。 */
    private final class Counts {

        private final int products = count("SELECT COUNT(*) FROM products");
        private final String sequence = value(
                "SELECT current_value FROM number_sequences WHERE sequence_key = 'products'");
        private final int changeLogs = count("SELECT COUNT(*) FROM master_data_change_logs");
        private final int audits = count("SELECT COUNT(*) FROM audit_logs");
        private final int idempotency = count("SELECT COUNT(*) FROM idempotency_records");

        private int count(String sql) {
            var value = jdbcTemplate.queryForObject(sql, Integer.class);
            return value == null ? 0 : value;
        }

        private String value(String sql) {
            return jdbcTemplate.query(sql, rs -> rs.next() ? rs.getString(1) : null);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Counts counts)) {
                return false;
            }
            return products == counts.products && changeLogs == counts.changeLogs
                    && audits == counts.audits && idempotency == counts.idempotency
                    && java.util.Objects.equals(sequence, counts.sequence);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(products, sequence, changeLogs, audits, idempotency);
        }
    }
}

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 2.24 商品缝边默认值：商品只保存「默认缝边剪袋类型（可空＝默认不缝边剪袋）+ 缝边价格」，
 * 商品自身总成本与参考售价按不缝边剪袋口径落库，缝边剪袋变体以 seamBudget 单列返回（公式 FP-PROD-20/21）。
 * 覆盖：默认值与名称/成本单价快照、快照不回溯与显式刷新、清空语义、两套预算、试算只读与错误码。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductSeamDefaultApiTest {

    private static final String USERNAME = "seam-default-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String STATIC_DATA = "/api/settings/static-data";

    /** 钉死算例：三星 std=15、270g、损耗 20%、成交价 25、无包装档位、其他成本 0.9 → 不缝边剪袋总成本 16.1200。 */
    private static final String BASE_FIELDS = """
            "starLevelId":3,"salePrice":"25.0000","weightG":270,
            "boxLaborFee":"0.5","transportPackingFee":"0.3","moldAmortFee":"0"
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
    private long seamTypeId;

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
        setSetting("packaging_commission_default", "0.000000");
        int[] mins = {5, 10, 15, 20, 30};
        for (int i = 0; i < mins.length; i++) {
            jdbcTemplate.update("UPDATE star_levels SET std_minutes = ? WHERE id = ?", mins[i], i + 1);
        }
        jdbcTemplate.update("DELETE FROM products WHERE name LIKE 'TST-缝边%'");
        jdbcTemplate.update("DELETE FROM seam_types WHERE name LIKE '验收缝边%'");
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
        seamTypeId = createSeamType("验收缝边-默认", "1.2500");
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM products WHERE name LIKE 'TST-缝边%'");
        jdbcTemplate.update("DELETE FROM seam_types WHERE name LIKE '验收缝边%'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
        for (var key : new String[]{"glue_unit_price", "colorpaste_unit_price",
                "box_labor_default", "transport_packing_default", "sundries_default", "rent_utilities_default",
                "packaging_commission_default"}) {
            setSetting(key, "0.000000");
        }
    }

    @Test
    void createStoresSeamDefaultAndReturnsBothBudgets() throws Exception {
        var created = mockMvc.perform(post("/api/products")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TST-缝边甲\"," + BASE_FIELDS
                                + ",\"seamTypeId\":" + seamTypeId + ",\"seamFee\":\"2.0000\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                // 商品自身按不缝边剪袋口径
                .andExpect(jsonPath("$.data.totalCost").value("16.1200"))
                .andExpect(jsonPath("$.data.referencePrice").value("23.0286"))
                // 缝边默认值：引用 + 名称与成本单价快照 + 缝边价格
                .andExpect(jsonPath("$.data.seamTypeId").value(seamTypeId))
                .andExpect(jsonPath("$.data.seamTypeName").value("验收缝边-默认"))
                .andExpect(jsonPath("$.data.seamTypeCostPrice").value("1.2500"))
                .andExpect(jsonPath("$.data.seamFee").value("2.0000"))
                // 缝边剪袋变体：16.1200 + 1.2500 = 17.3700；17.37/0.7 = 24.8142.85… → 24.8143
                .andExpect(jsonPath("$.data.seamBudget.seamUnitCost").value("1.2500"))
                .andExpect(jsonPath("$.data.seamBudget.seamFee").value("2.0000"))
                .andExpect(jsonPath("$.data.seamBudget.totalCost").value("17.3700"))
                .andExpect(jsonPath("$.data.seamBudget.referencePrice").value("24.8143"))
                .andReturn();

        var id = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        // 落库口径：商品成本列为不缝边剪袋口径，缝边只存默认值与成本单价快照
        var row = jdbcTemplate.queryForMap(
                "SELECT total_cost, reference_price, seam_type_id, seam_type_name, seam_type_cost_price, seam_fee "
                        + "FROM products WHERE id = ?", id);
        assertThat(String.valueOf(row.get("total_cost"))).isEqualTo("16.1200");
        assertThat(String.valueOf(row.get("reference_price"))).isEqualTo("23.0286");
        assertThat(((Number) row.get("seam_type_id")).longValue()).isEqualTo(seamTypeId);
        assertThat(row.get("seam_type_name")).isEqualTo("验收缝边-默认");
        assertThat(String.valueOf(row.get("seam_type_cost_price"))).isEqualTo("1.2500");
        assertThat(String.valueOf(row.get("seam_fee"))).isEqualTo("2.0000");
    }

    @Test
    void createWithoutSeamTypeMeansNoSeamVariant() throws Exception {
        mockMvc.perform(post("/api/products")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TST-缝边乙\"," + BASE_FIELDS + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.seamTypeId").doesNotExist())
                .andExpect(jsonPath("$.data.seamTypeName").doesNotExist())
                .andExpect(jsonPath("$.data.seamFee").value("0.0000"))
                .andExpect(jsonPath("$.data.seamBudget").doesNotExist())
                .andExpect(jsonPath("$.data.totalCost").value("16.1200"));
    }

    @Test
    void editKeepsSnapshotThenRefreshesSwitchesAndClearsSeamType() throws Exception {
        var created = create("TST-缝边丙");
        var id = created.path("id").asLong();
        var version = created.path("version").asLong();
        assertThat(created.path("seamBudget").path("totalCost").asText()).isEqualTo("17.3700");

        // 条目改值不回溯：同引用再保存仍用商品快照 1.2500
        patchSeamType(seamTypeId, "9.0000");
        var sameReference = patchProduct(id,
                "{\"version\":" + version + ",\"seamTypeId\":" + seamTypeId + "}");
        assertThat(sameReference.path("seamTypeCostPrice").asText()).isEqualTo("1.2500");
        assertThat(sameReference.path("seamBudget").path("totalCost").asText()).isEqualTo("17.3700");
        version = sameReference.path("version").asLong();

        // 显式刷新才采纳当前条目值：16.1200 + 9.0000 = 25.1200；25.12/0.7 → 35.8857
        var refreshed = patchProduct(id, "{\"version\":" + version + ",\"refreshGlobalReferences\":true}");
        assertThat(refreshed.path("seamTypeCostPrice").asText()).isEqualTo("9.0000");
        assertThat(refreshed.path("seamBudget").path("totalCost").asText()).isEqualTo("25.1200");
        assertThat(refreshed.path("seamBudget").path("referencePrice").asText()).isEqualTo("35.8857");
        assertThat(refreshed.path("totalCost").asText()).isEqualTo("16.1200");
        version = refreshed.path("version").asLong();

        // 换成不同引用取当前条目：16.1200 + 3.0000 = 19.1200
        var otherId = createSeamType("验收缝边-默认乙", "3.0000");
        var switched = patchProduct(id, "{\"version\":" + version + ",\"seamTypeId\":" + otherId + "}");
        assertThat(switched.path("seamTypeName").asText()).isEqualTo("验收缝边-默认乙");
        assertThat(switched.path("seamTypeCostPrice").asText()).isEqualTo("3.0000");
        assertThat(switched.path("seamBudget").path("totalCost").asText()).isEqualTo("19.1200");
        version = switched.path("version").asLong();

        // 清空＝默认不缝边剪袋：缝边价格保留，缝边剪袋变体消失
        mockMvc.perform(patch("/api/products/" + id)
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + ",\"clearSeamType\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seamTypeId").doesNotExist())
                .andExpect(jsonPath("$.data.seamBudget").doesNotExist())
                .andExpect(jsonPath("$.data.seamFee").value("2.0000"))
                .andExpect(jsonPath("$.data.totalCost").value("16.1200"));

        // 选择与清空互斥
        mockMvc.perform(patch("/api/products/" + id)
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + (version + 1) + ",\"seamTypeId\":" + otherId
                                + ",\"clearSeamType\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='clearSeamType')]").isNotEmpty());
    }

    @Test
    void editPreviewFollowsClearSemanticsInsteadOfKeepingStaleVariant() throws Exception {
        var id = create("TST-缝边庚").path("id").asLong();

        // 未传缝边字段：沿用商品快照，仍返回缝边剪袋变体（克重 300 → 不缝边剪袋 17.2000，变体 +1.2500）
        mockMvc.perform(post("/api/products/" + id + "/preview")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"weightG\":300}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCost").value("17.2000"))
                .andExpect(jsonPath("$.data.seamBudget.totalCost").value("18.4500"));

        // 只传 seamTypeId=null：按 PATCH 缺省合并仍保持原值，不视为清空
        mockMvc.perform(post("/api/products/" + id + "/preview")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"seamTypeId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seamBudget.totalCost").value("17.3700"));

        // 显式清空：不再返回缝边剪袋变体（前端编辑页清空后必须走这条路径）
        mockMvc.perform(post("/api/products/" + id + "/preview")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"clearSeamType\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seamBudget").doesNotExist())
                .andExpect(jsonPath("$.data.totalCost").value("16.1200"));
    }

    @Test
    void rejectsUnknownSeamTypeAndNegativeSeamFee() throws Exception {
        mockMvc.perform(post("/api/products")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TST-缝边丁\"," + BASE_FIELDS + ",\"seamTypeId\":999999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='seamTypeId')]").isNotEmpty());

        mockMvc.perform(post("/api/products")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TST-缝边戊\"," + BASE_FIELDS + ",\"seamFee\":\"-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='seamFee')]").isNotEmpty());
    }

    @Test
    void previewReturnsSeamVariantWithoutBusinessWrites() throws Exception {
        var id = create("TST-缝边己").path("id").asLong();
        var before = new Counts();

        mockMvc.perform(post("/api/products/preview")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + BASE_FIELDS + ",\"seamTypeId\":" + seamTypeId
                                + ",\"seamFee\":\"2.0000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCost").value("16.1200"))
                .andExpect(jsonPath("$.data.seamBudget.totalCost").value("17.3700"))
                .andExpect(jsonPath("$.data.seamBudget.referencePrice").value("24.8143"))
                .andExpect(jsonPath("$.data.seamBudget.seamFee").value("2.0000"));

        mockMvc.perform(post("/api/products/" + id + "/preview")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"weightG\":300}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.glueGrams").value(360))
                .andExpect(jsonPath("$.data.seamBudget.seamUnitCost").value("1.2500"));

        assertThat(before.equals(new Counts()))
                .as("只读试算不得新增商品、编号、变更日志、写审计或幂等记录")
                .isTrue();
    }

    // ---------- 工具 ----------

    private long createSeamType(String name, String costPrice) throws Exception {
        var created = mockMvc.perform(post(STATIC_DATA + "/SEAM_TYPE/items")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"costPrice\":\"" + costPrice + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private void patchSeamType(long id, String costPrice) throws Exception {
        mockMvc.perform(patch(STATIC_DATA + "/SEAM_TYPE/items/" + id)
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"costPrice\":\"" + costPrice + "\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode create(String name) throws Exception {
        var created = mockMvc.perform(post("/api/products")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"," + BASE_FIELDS
                                + ",\"seamTypeId\":" + seamTypeId + ",\"seamFee\":\"2.0000\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).path("data");
    }

    private JsonNode patchProduct(long id, String body) throws Exception {
        var updated = mockMvc.perform(patch("/api/products/" + id)
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(updated.getResponse().getContentAsString()).path("data");
    }

    private void setSetting(String key, String value) {
        jdbcTemplate.update("UPDATE catalog_settings SET setting_value = ? WHERE setting_key = ?",
                new java.math.BigDecimal(value), key);
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private final class Counts {

        private final int products = count("SELECT COUNT(*) FROM products");
        private final int sequences = count("SELECT COALESCE(SUM(current_value), 0) FROM number_sequences");
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
            return products == counts.products && sequences == counts.sequences
                    && changeLogs == counts.changeLogs && audits == counts.audits
                    && idempotency == counts.idempotency;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(products, sequences, changeLogs, audits, idempotency);
        }
    }
}

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
 * 任务 2.2 商品 API：信封、编号、幂等、筛选、版本冲突、变更日志、图片挂接。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductApiTest {

    private static final String USERNAME = "p-test-admin";
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
        jdbcTemplate.update("DELETE FROM products WHERE name LIKE 'TST-%'");
        jdbcTemplate.update("DELETE FROM file_metadata WHERE object_key LIKE 'test-fixture/%'");
        sessionCookie = login();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM products WHERE name LIKE 'TST-%'");
        jdbcTemplate.update("DELETE FROM file_metadata WHERE object_key LIKE 'test-fixture/%'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
        restoreZeroSettings();
    }

    private void setSetting(String key, String value) {
        jdbcTemplate.update("UPDATE catalog_settings SET setting_value = ? WHERE setting_key = ?",
                new java.math.BigDecimal(value), key);
    }

    private void restoreZeroSettings() {
        for (var key : new String[]{"glue_unit_price", "colorpaste_unit_price", "loss_rate_default",
                "box_labor_default", "transport_packing_default", "sundries_default", "rent_utilities_default"}) {
            setSetting(key, "0.000000");
        }
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

    private String createBody(String name) {
        return """
                {"name":"%s","starLevelId":3,"moldQuantity":10,"dailyBatchLimit":5,"salePrice":"25.0000","weightG":270,"lossRatePercent":"20",
                 "seamMinutes":"5","seamDefaultFee":"0",
                 "boxLaborFee":"0.5","transportPackingFee":"0.3","dailySundriesFee":"0.2",
                 "rentUtilitiesFee":"0.4","moldAmortFee":"0.1"}
                """.formatted(name);
    }

    private JsonNode createProduct(String name) throws Exception {
        var body = mockMvc.perform(post("/api/products")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(name)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    @Test
    void createsProductsWithMonotonicPNumberInEnvelope() throws Exception {
        var first = mockMvc.perform(post("/api/products")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("TST-创建甲")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.name").value("TST-创建甲"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andReturn();
        var firstNo = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("data").path("productNo").asText();
        assertThat(firstNo).matches("P\\d{5}");

        var second = mockMvc.perform(post("/api/products")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("TST-创建乙")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andReturn();
        var secondNo = objectMapper.readTree(second.getResponse().getContentAsString())
                .path("data").path("productNo").asText();
        assertThat(secondNo).matches("P\\d{5}");
        assertThat(Integer.parseInt(secondNo.substring(1)))
                .isEqualTo(Integer.parseInt(firstNo.substring(1)) + 1);
    }

    @Test
    void rejectsCreateWithoutNameWithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/products")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"starLevelId":3,"moldQuantity":10,"dailyBatchLimit":5,"salePrice":"1.0000","weightG":100,"lossRatePercent":"0",
                                 "seamMinutes":"0","seamDefaultFee":"0"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='name')]").isNotEmpty());

        mockMvc.perform(post("/api/products")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"   ","starLevelId":3,"moldQuantity":10,"dailyBatchLimit":5,"salePrice":"1.0000","weightG":100,
                                 "lossRatePercent":"0","seamMinutes":"0","seamDefaultFee":"0"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='name')]").isNotEmpty());
    }

    @Test
    void rejectsWriteWithoutIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/products")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("TST-幂等")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"));
    }

    @Test
    void filtersListByStatusAndName() throws Exception {
        createProduct("TST-筛选甲");
        var b = createProduct("TST-筛选乙");

        mockMvc.perform(post("/api/products/" + b.path("id").asLong() + "/disable")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        mockMvc.perform(get("/api/products").cookie(sessionCookie).param("status", "DISABLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data[?(@.name=='TST-筛选乙')]").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.name=='TST-筛选甲')]").isEmpty());

        mockMvc.perform(get("/api/products").cookie(sessionCookie).param("name", "筛选甲"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.name=='TST-筛选甲')]").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.name=='TST-筛选乙')]").isEmpty());

        var summary = mockMvc.perform(get("/api/products").cookie(sessionCookie)
                        .param("name", "TST-筛选甲"))
                .andExpect(status().isOk())
                .andReturn();
        var item = objectMapper.readTree(summary.getResponse().getContentAsString())
                .path("data").get(0);
        assertThat(item.path("productNo").asText()).matches("P\\d{5}");
        assertThat(item.path("salePrice").asText()).isEqualTo("25.0000");
        assertThat(item.path("totalCost").asText()).isEqualTo("16.2200");
        assertThat(item.path("starLevelId").asInt()).isEqualTo(3);
    }

    @Test
    void patchWithStaleVersionConflicts() throws Exception {
        var created = createProduct("TST-版本");
        var id = created.path("id").asLong();

        mockMvc.perform(patch("/api/products/" + id)
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"name\":\"TST-版本改\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.name").value("TST-版本改"));

        mockMvc.perform(patch("/api/products/" + id)
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"name\":\"TST-版本再改\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT_VERSION"));
    }

    @Test
    void missingVersionFieldRejected() throws Exception {
        var created = createProduct("TST-缺版本");
        mockMvc.perform(patch("/api/products/" + created.path("id").asLong())
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TST-缺版本改\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='version')]").isNotEmpty());
    }

    @Test
    void editsAndToggleAppendChangeLogRows() throws Exception {
        var created = createProduct("TST-日志");
        var id = created.path("id").asLong();

        mockMvc.perform(patch("/api/products/" + id)
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"name\":\"TST-日志一\",\"reason\":\"首次改名\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/products/" + id)
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"salePrice\":\"30.0000\",\"reason\":\"调价\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/products/" + id + "/disable")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"暂停销售\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
        mockMvc.perform(post("/api/products/" + id + "/enable")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        var rows = jdbcTemplate.queryForList(
                "SELECT id, before_json, after_json, reason, admin_username, request_id "
                        + "FROM master_data_change_logs WHERE entity_type='PRODUCT' AND entity_id=? ORDER BY id", id);
        // 两次编辑 + disable + enable = 4 行，追加不覆盖
        assertThat(rows).hasSize(4);
        assertThat(rows).extracting(r -> r.get("admin_username")).containsOnly("p-test-admin");
        assertThat(rows).extracting(r -> String.valueOf(r.get("request_id")))
                .allSatisfy(requestId -> assertThat(requestId).isNotBlank());
        assertThat(String.valueOf(rows.get(0).get("reason"))).isEqualTo("首次改名");
        assertThat(String.valueOf(rows.get(1).get("reason"))).isEqualTo("调价");
        assertThat(String.valueOf(rows.get(2).get("reason"))).isEqualTo("暂停销售");
        assertThat(String.valueOf(rows.get(0).get("before_json"))).contains("TST-日志");
        assertThat(String.valueOf(rows.get(0).get("after_json"))).contains("TST-日志一");
        assertThat(String.valueOf(rows.get(2).get("after_json"))).contains("DISABLED");
    }

    @Test
    void rejectsUnknownImageFileAndAcceptsExistingFixture() throws Exception {
        mockMvc.perform(post("/api/products")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"TST-坏图","starLevelId":3,"moldQuantity":10,"dailyBatchLimit":5,"salePrice":"1.0000","weightG":100,
                                 "lossRatePercent":"0","seamMinutes":"0","seamDefaultFee":"0",
                                 "imageFileId":999999991}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='imageFileId')]").isNotEmpty());

        var fileId = insertFileFixture();
        var body = mockMvc.perform(post("/api/products")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"TST-好图","starLevelId":3,"moldQuantity":10,"dailyBatchLimit":5,"salePrice":"1.0000","weightG":100,
                                 "lossRatePercent":"0","seamMinutes":"0","seamDefaultFee":"0",
                                 "imageFileId":%d}
                                """.formatted(fileId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.imageFileId").value(fileId))
                .andReturn();
        assertThat(body.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void returns404ForUnknownProduct() throws Exception {
        mockMvc.perform(get("/api/products/999999991").cookie(sessionCookie))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(patch("/api/products/999999991")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"name\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void getDetailReturnsAllFieldsAndDerivedValues() throws Exception {
        var created = createProduct("TST-详情");
        var id = created.path("id").asLong();

        mockMvc.perform(get("/api/products/" + id).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.productNo").value(created.path("productNo").asText()))
                .andExpect(jsonPath("$.data.name").value("TST-详情"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.starLevelId").value(3))
                .andExpect(jsonPath("$.data.starName").value("三星"))
                .andExpect(jsonPath("$.data.starStdMinutes").value(15))
                .andExpect(jsonPath("$.data.salePrice").value("25.0000"))
                .andExpect(jsonPath("$.data.weightG").value(270))
                .andExpect(jsonPath("$.data.lossRatePercent").value("20.000000"))
                .andExpect(jsonPath("$.data.glueUnitPrice").value("0.0100"))
                .andExpect(jsonPath("$.data.colorpasteUnitPrice").value("0.0200"))
                .andExpect(jsonPath("$.data.boxLaborFee").value("0.5000"))
                .andExpect(jsonPath("$.data.transportPackingFee").value("0.3000"))
                .andExpect(jsonPath("$.data.dailySundriesFee").value("0.2000"))
                .andExpect(jsonPath("$.data.rentUtilitiesFee").value("0.4000"))
                .andExpect(jsonPath("$.data.moldAmortFee").value("0.1000"))
                .andExpect(jsonPath("$.data.packagingTierId").doesNotExist())
                .andExpect(jsonPath("$.data.packagingLaborFee").value("0.0000"))
                .andExpect(jsonPath("$.data.totalCost").value("16.2200"))
                .andExpect(jsonPath("$.data.referencePrice").value("23.1714"))
                .andExpect(jsonPath("$.data.estimatedProfit").value("8.7800"))
                .andExpect(jsonPath("$.data.estimatedMarginRate").value("0.351200"))
                .andExpect(jsonPath("$.data.version").value(0));
    }

    private long insertFileFixture() {
        var uuid = UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("""
                INSERT INTO file_metadata (
                    object_key, original_filename, content_type, content_length, sha256,
                    version, created_at, updated_at
                ) VALUES (?, 'fixture.png', 'image/png', 1, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, "test-fixture/" + uuid, uuid + uuid);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM file_metadata WHERE object_key = ?", Long.class, "test-fixture/" + uuid);
    }
}

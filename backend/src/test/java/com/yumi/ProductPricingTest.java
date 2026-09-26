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
 * 任务 2.3 计价公式钉死：胶水/色浆、星级人工、包装、缝边、总成本与读时派生。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductPricingTest {

    private static final String USERNAME = "p-test-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";

    /** 钉死算例公共参数：weight=270、loss=20%、star=3(std15)、seam=5、sale=25、无包装档位。 */
    private static final String PINNED_BODY = """
            {"name":"%s","starLevelId":3,"moldQuantity":10,"dailyBatchLimit":5,"salePrice":"%s","weightG":270,"lossRatePercent":"20",
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
        sessionCookie = login();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM products WHERE name LIKE 'TST-%'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
        for (var key : new String[]{"glue_unit_price", "colorpaste_unit_price", "loss_rate_default",
                "box_labor_default", "transport_packing_default", "sundries_default", "rent_utilities_default"}) {
            setSetting(key, "0.000000");
        }
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

    @Test
    void pinnedExampleMatchesEveryFormula() throws Exception {
        var data = create(PINNED_BODY.formatted("TST-钉死", "25.0000"));

        // 胶水/色浆：glueGrams = round(270*1.2)=324
        assertThat(data.path("glueGrams").asInt()).isEqualTo(324);
        assertThat(data.path("glueCost").asText()).isEqualTo("3.2400");
        assertThat(data.path("colorpasteCost").asText()).isEqualTo("6.4800");
        assertThat(data.path("glueUnitPrice").asText()).isEqualTo("0.0100");
        assertThat(data.path("colorpasteUnitPrice").asText()).isEqualTo("0.0200");
        assertThat(data.path("lossRatePercent").asText()).isEqualTo("20.000000");

        // 星级：std=15 → qty8=floor(480/15)=32、qty6=floor(360/15)=24、120/24=5.0000
        assertThat(data.path("starName").asText()).isEqualTo("三星");
        assertThat(data.path("starStdMinutes").asInt()).isEqualTo(15);
        assertThat(data.path("qty8h").asInt()).isEqualTo(32);
        assertThat(data.path("qty6h").asInt()).isEqualTo(24);
        assertThat(data.path("productLaborFee").asText()).isEqualTo("5.0000");

        // 包装：无档位 → 0 且快照 null
        assertThat(data.path("packagingTierId").isNull()).isTrue();
        assertThat(data.path("packagingTierName").isNull()).isTrue();
        assertThat(data.path("packagingLaborFee").asText()).isEqualTo("0.0000");

        // 缝边：5*0.25=1.2500；1.2500/0.7=1.7857；不计入基础总成本

        // 三者和与参考价
        assertThat(data.path("materialCost").asText()).isEqualTo("9.7200");
        assertThat(data.path("laborCost").asText()).isEqualTo("5.5000");
        assertThat(data.path("otherCost").asText()).isEqualTo("1.0000");
        assertThat(data.path("totalCost").asText()).isEqualTo("16.2200");
        assertThat(data.path("referencePrice").asText()).isEqualTo("23.1714");

        // 读时派生
        assertThat(data.path("salePrice").asText()).isEqualTo("25.0000");
        assertThat(data.path("estimatedProfit").asText()).isEqualTo("8.7800");
        assertThat(data.path("estimatedMarginRate").asText()).isEqualTo("0.351200");
    }

    @Test
    void zeroSalePriceYieldsZeroMarginWithoutDivisionError() throws Exception {
        var data = create(PINNED_BODY.formatted("TST-零价", "0.0000"));
        var detail = getDetail(data.path("id").asLong());
        assertThat(detail.path("salePrice").asText()).isEqualTo("0.0000");
        assertThat(detail.path("totalCost").asText()).isEqualTo("16.2200");
        assertThat(detail.path("estimatedMarginRate").asText()).isEqualTo("0.000000");
        assertThat(detail.path("estimatedProfit").asText()).isEqualTo("-16.2200");
    }

    @Test
    void starChangeRecomputesQuantitiesAndLabor() throws Exception {
        var created = create(PINNED_BODY.formatted("TST-改星", "25.0000"));
        var data = patchProduct(created.path("id").asLong(), 0, "{\"version\":%d,\"starLevelId\":1}");

        assertThat(data.path("starLevelId").asInt()).isEqualTo(1);
        assertThat(data.path("starName").asText()).isEqualTo("一星");
        assertThat(data.path("starStdMinutes").asInt()).isEqualTo(5);
        assertThat(data.path("qty8h").asInt()).isEqualTo(96);
        assertThat(data.path("qty6h").asInt()).isEqualTo(72);
        // 120/72 = 1.666666... HALF_UP scale4
        assertThat(data.path("productLaborFee").asText()).isEqualTo("1.6667");
        assertThat(data.path("laborCost").asText()).isEqualTo("2.1667");
        assertThat(data.path("totalCost").asText()).isEqualTo("12.8867");
    }

    @Test
    void refreshMaterialPricesFlagReReadsGlobalUnitPrices() throws Exception {
        var created = create(PINNED_BODY.formatted("TST-刷新料价", "25.0000"));
        var id = created.path("id").asLong();

        setSetting("glue_unit_price", "0.020000");

        // 不带 refreshMaterialPrices：保留商品存量单价
        var kept = patchProduct(id, 0, "{\"version\":%d,\"note\":\"仅改备注\"}");
        assertThat(kept.path("glueUnitPrice").asText()).isEqualTo("0.0100");
        assertThat(kept.path("glueCost").asText()).isEqualTo("3.2400");
        assertThat(kept.path("totalCost").asText()).isEqualTo("16.2200");

        // 带 refreshMaterialPrices=true：按当前全局单价重算
        var refreshed = patchProduct(id, 1, "{\"version\":%d,\"refreshMaterialPrices\":true}");
        assertThat(refreshed.path("glueUnitPrice").asText()).isEqualTo("0.0200");
        assertThat(refreshed.path("colorpasteUnitPrice").asText()).isEqualTo("0.0200");
        // 324*0.02=6.4800；material=6.48+6.48=12.9600；total=12.96+5.5+1.0=19.4600
        assertThat(refreshed.path("glueCost").asText()).isEqualTo("6.4800");
        assertThat(refreshed.path("materialCost").asText()).isEqualTo("12.9600");
        assertThat(refreshed.path("totalCost").asText()).isEqualTo("19.4600");
        assertThat(refreshed.path("referencePrice").asText()).isEqualTo("27.8000");
    }

    @Test
    void weightChangeCascadesThroughMaterialToTotalAndReferencePrice() throws Exception {
        var created = create(PINNED_BODY.formatted("TST-改重", "25.0000"));
        assertThat(created.path("totalCost").asText()).isEqualTo("16.2200");
        assertThat(created.path("referencePrice").asText()).isEqualTo("23.1714");

        var data = patchProduct(created.path("id").asLong(), 0, "{\"version\":%d,\"weightG\":300}");

        // 300*1.2=360；360*0.01=3.6000；360*0.02=7.2000；material=10.8000
        assertThat(data.path("glueGrams").asInt()).isEqualTo(360);
        assertThat(data.path("glueCost").asText()).isEqualTo("3.6000");
        assertThat(data.path("colorpasteCost").asText()).isEqualTo("7.2000");
        assertThat(data.path("materialCost").asText()).isEqualTo("10.8000");
        assertThat(data.path("totalCost").asText()).isEqualTo("17.3000");
        assertThat(data.path("referencePrice").asText()).isEqualTo("24.7143");
        assertThat(data.path("estimatedProfit").asText()).isEqualTo("7.7000");
        assertThat(data.path("estimatedMarginRate").asText()).isEqualTo("0.308000");
    }

    @Test
    void packagingTierDrivesPackagingLaborFee() throws Exception {
        var uuid = UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("""
                INSERT INTO packaging_tiers (tier_name, std_minutes, version, created_at, updated_at)
                VALUES (?, 10.000, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, "TST-tier-" + uuid);
        var tierId = jdbcTemplate.queryForObject(
                "SELECT id FROM packaging_tiers WHERE tier_name = ?", Long.class, "TST-tier-" + uuid);
        try {
            var body = """
                    {"name":"TST-包装档","starLevelId":3,"moldQuantity":10,"dailyBatchLimit":5,"salePrice":"25.0000","weightG":270,
                     "lossRatePercent":"20","packagingTierId":%d,"packagingCommission":"0.2000",
                     "boxLaborFee":"0.5","transportPackingFee":"0.3","dailySundriesFee":"0.2",
                     "rentUtilitiesFee":"0.4","moldAmortFee":"0.1"}
                    """.formatted(tierId);
            var data = create(body);
            // 10*0.25 + 0.2 = 2.7000
            assertThat(data.path("packagingTierId").asLong()).isEqualTo(tierId);
            assertThat(data.path("packagingLaborFee").asText()).isEqualTo("2.7000");
            assertThat(data.path("laborCost").asText()).isEqualTo("8.2000");
            assertThat(data.path("totalCost").asText()).isEqualTo("18.9200");
        } finally {
            jdbcTemplate.update("DELETE FROM packaging_tiers WHERE id = ?", tierId);
        }
    }

    @Test
    void zeroQty6ProducesZeroLaborInsteadOfDivisionError() throws Exception {
        // star_levels 中 std 最大为 30：floor(360/30)=12，构造 qty6=0 需注入临时星级行
        jdbcTemplate.update("""
                UPDATE star_levels SET std_minutes = 400 WHERE id = 5
                """);
        try {
            var body = """
                    {"name":"TST-零产量","starLevelId":5,"moldQuantity":10,"dailyBatchLimit":5,"salePrice":"10.0000","weightG":100,
                     "lossRatePercent":"0","seamMinutes":"0","seamDefaultFee":"0",
                     "boxLaborFee":"0","transportPackingFee":"0","dailySundriesFee":"0",
                     "rentUtilitiesFee":"0","moldAmortFee":"0"}
                    """;
            var data = create(body);
            assertThat(data.path("qty8h").asInt()).isEqualTo(1);
            assertThat(data.path("qty6h").asInt()).isEqualTo(0);
            assertThat(data.path("productLaborFee").asText()).isEqualTo("0.0000");
            assertThat(data.path("laborCost").asText()).isEqualTo("0.0000");
        } finally {
            jdbcTemplate.update("UPDATE star_levels SET std_minutes = 30 WHERE id = 5");
        }
    }
}

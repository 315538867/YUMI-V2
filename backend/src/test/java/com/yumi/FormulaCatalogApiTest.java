package com.yumi;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 2.20 只读公式说明：GET /api/settings/formulas 返回 calculation 模块的静态公式目录。
 * 覆盖认证、统一信封、按业务分组、目录完整性、不要求幂等键且不产生业务写入。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FormulaCatalogApiTest {

    private static final String USERNAME = "formula-catalog-admin";
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
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
    }

    @Test
    void formulasRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/settings/formulas"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void formulasReturnImplementedProductCatalogGroupedByBusiness() throws Exception {
        mockMvc.perform(get("/api/settings/formulas").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.groups.length()").value(1))
                .andExpect(jsonPath("$.data.groups[0].category").value("商品"))
                .andExpect(jsonPath("$.data.groups[0].formulas.length()").value(17))
                .andExpect(jsonPath("$.data.groups[0].formulas[0].identifier").value("FP-PROD-01"))
                .andExpect(jsonPath("$.data.groups[0].formulas[0].name").value("损耗率换算"))
                .andExpect(jsonPath("$.data.groups[0].formulas[0].inputs").value("百分比文本 %"))
                .andExpect(jsonPath("$.data.groups[0].formulas[0].expression").value("percent ÷ 100"))
                .andExpect(jsonPath("$.data.groups[0].formulas[0].rounding").value("scale6 HALF_UP"))
                .andExpect(jsonPath("$.data.groups[0].formulas[0].resultMeaning").value("内部损耗比例"))
                .andExpect(jsonPath("$.data.groups[0].formulas[0].example").value("33.3333 → 0.333333"))
                .andExpect(jsonPath("$.data.groups[0].formulas[0].codeLocation")
                        .value("calculation/DecimalPolicy.percentToRatio"))
                .andExpect(jsonPath("$.data.groups[0].formulas[0].testId")
                        .value("ProductPricingBaselineTest#lossRateConversionUsesScale6HalfUp"));
    }

    @Test
    void catalogEntriesAreCompleteAndUnique() throws Exception {
        var body = mockMvc.perform(get("/api/settings/formulas").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var formulas = objectMapper.readTree(body).path("data").path("groups").get(0).path("formulas");

        var identifiers = new ArrayList<String>();
        for (JsonNode formula : formulas) {
            identifiers.add(formula.path("identifier").asText());
            for (var field : List.of("identifier", "name", "inputs", "expression", "rounding",
                    "resultMeaning", "example", "codeLocation", "testId")) {
                assertThat(formula.path(field).asText())
                        .as("公式 %s 的 %s 不得为空", formula.path("identifier").asText(), field)
                        .isNotBlank();
            }
            assertThat(formula.path("testId").asText()).contains("#");
        }
        assertThat(identifiers).doesNotHaveDuplicates().hasSize(17);
        assertThat(identifiers.get(0)).isEqualTo("FP-PROD-01");
        assertThat(identifiers.get(16)).isEqualTo("FP-PROD-19");
    }

    @Test
    void formulasDoNotRequireIdempotencyKeyAndWriteNothing() throws Exception {
        var before = new Counts();

        // 不带 Idempotency-Key 的 GET 直接成功
        mockMvc.perform(get("/api/settings/formulas").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        assertThat(before.equals(new Counts()))
                .as("只读公式说明不得产生商品、变更日志、写审计或幂等记录")
                .isTrue();
    }

    /** 只读查询前后的业务写入计数快照。 */
    private final class Counts {

        private final int products = count("SELECT COUNT(*) FROM products");
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
            return products == counts.products && changeLogs == counts.changeLogs
                    && audits == counts.audits && idempotency == counts.idempotency;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(products, changeLogs, audits, idempotency);
        }
    }
}

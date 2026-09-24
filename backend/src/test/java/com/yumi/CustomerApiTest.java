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
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 2.5 客户 API 黑盒测试：C 编号创建、重名/重电话只提示不入库、
 * duplicateConfirmed 后独立编号独立行（绝不合并）、必填校验、幂等键、
 * 乐观锁 409 与追加式变更日志。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerApiTest {

    private static final String CUSTOMERS = "/api/customers";
    private static final String USERNAME = "c-test-admin";
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
    void loginAndCleanTestCustomers() throws Exception {
        // 所有本类客户名统一前缀，测试间清空互不影响他域数据
        jdbcTemplate.update("DELETE FROM customers WHERE name LIKE '客户测试-%'");
        jdbcTemplate.update("DELETE FROM idempotency_records WHERE idempotency_key LIKE 'cust-key-%'");
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

    private String body(String name, String phone, boolean duplicateConfirmed) {
        return """
                {"name":"%s","contact":"王助理","phone":"%s","note":"原始备注",
                 "defaultRecipient":"收件人甲","defaultRecipientPhone":"13700000001",
                 "defaultRegion":"浙江省杭州市余杭区","defaultAddress":"文一西路1号",
                 "duplicateConfirmed":%s}
                """.formatted(name, phone, duplicateConfirmed);
    }

    private String key() {
        return "cust-key-" + UUID.randomUUID();
    }

    private ResultActions postCustomer(String body, String idempotencyKey) throws Exception {
        var request = post(CUSTOMERS).cookie(sessionCookie).contentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request.content(body));
    }

    private int customerRows(String name) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM customers WHERE name = ?", Integer.class, name);
    }

    private String customerNoByName(String name) {
        return jdbcTemplate.queryForObject("SELECT customer_no FROM customers WHERE name = ? ORDER BY id LIMIT 1",
                String.class, name);
    }

    @Test
    void createsCustomerWithCNumberInEnvelope() throws Exception {
        var name = "客户测试-创建甲";
        postCustomer(body(name, "13800000001", false), key())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.customerNo", matchesPattern("C\\d{5}")))
                .andExpect(jsonPath("$.data.name").value(name))
                .andExpect(jsonPath("$.data.contact").value("王助理"))
                .andExpect(jsonPath("$.data.phone").value("13800000001"))
                .andExpect(jsonPath("$.data.defaultRecipient").value("收件人甲"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.createdAt").isString())
                .andExpect(jsonPath("$.data.updatedAt").isString());

        assertThat(customerNoByName(name)).matches("C\\d{5}");
    }

    @Test
    void noCandidateCreatesDirectly() throws Exception {
        postCustomer(body("客户测试-无候选甲", "13800000002", false), key())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.customerNo", matchesPattern("C\\d{5}")));
        assertThat(customerRows("客户测试-无候选甲")).isEqualTo(1);
    }

    @Test
    void duplicateNameFirstSubmitOnlyPromptsWithoutInserting() throws Exception {
        var name = "客户测试-重名甲";
        var firstNo = postCustomer(body(name, "13800000003", false), key())
                .andExpect(status().isCreated())
                .andReturn();
        var createdNo = objectMapper.readTree(firstNo.getResponse().getContentAsString())
                .get("data").get("customerNo").asText();

        var rowsBefore = customerRows(name);

        postCustomer(body(name, "13800000099", false), key())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.created").value(false))
                .andExpect(jsonPath("$.data.duplicateCandidates[0].customerNo").value(createdNo))
                .andExpect(jsonPath("$.data.duplicateCandidates[0].name").value(name))
                .andExpect(jsonPath("$.data.duplicateCandidates[0].phone").value("13800000003"));

        assertThat(customerRows(name)).as("首提重复提示不得入库").isEqualTo(rowsBefore);
    }

    @Test
    void confirmedDuplicateCreatesIndependentRowWithOwnNumber() throws Exception {
        var name = "客户测试-确认重复甲";
        var firstNo = objectMapper.readTree(
                        postCustomer(body(name, "13800000004", false), key())
                                .andExpect(status().isCreated())
                                .andReturn().getResponse().getContentAsString())
                .get("data").get("customerNo").asText();

        var secondNo = objectMapper.readTree(
                        postCustomer(body(name, "13800000004", true), key())
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.data.customerNo", matchesPattern("C\\d{5}")))
                                .andReturn().getResponse().getContentAsString())
                .get("data").get("customerNo").asText();

        assertThat(secondNo).as("确认后必须是新编号").isNotEqualTo(firstNo);
        assertThat(customerRows(name)).as("确认重复=两行独立并存，绝不合并").isEqualTo(2);
        var numbers = jdbcTemplate.queryForList(
                "SELECT customer_no FROM customers WHERE name = ?", String.class, name);
        assertThat(numbers).containsExactlyInAnyOrder(firstNo, secondNo);
    }

    @Test
    void phoneOnlyDuplicateAlsoPrompts() throws Exception {
        postCustomer(body("客户测试-电话重复甲", "13900000100", false), key())
                .andExpect(status().isCreated());

        postCustomer(body("客户测试-电话重复乙", "13900000100", false), key())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(false))
                .andExpect(jsonPath("$.data.duplicateCandidates[0].name").value("客户测试-电话重复甲"))
                .andExpect(jsonPath("$.data.duplicateCandidates[0].phone").value("13900000100"));

        assertThat(customerRows("客户测试-电话重复乙")).as("仅电话重复也不得入库").isZero();
    }

    @Test
    void missingRequiredFieldReturnsValidationInvalidWithFieldError() throws Exception {
        postCustomer("""
                {"contact":"王助理","phone":"13800000005",
                 "defaultRecipient":"收件人甲","defaultRecipientPhone":"13700000001",
                 "defaultRegion":"浙江省杭州市余杭区","defaultAddress":"文一西路1号"}
                """, key())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void writeWithoutIdempotencyKeyIsRejected() throws Exception {
        postCustomer(body("客户测试-无幂等键", "13800000006", false), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"));
    }

    @Test
    void patchWithStaleVersionReturnsConflictVersion() throws Exception {
        var created = objectMapper.readTree(postCustomer(body("客户测试-旧版本", "13800000007", false), key())
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("data");
        var id = created.get("id").asLong();

        mockMvc.perform(patch(CUSTOMERS + "/" + id).cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content("{\"version\":0,\"note\":\"第一版备注\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));

        // 库中 version 已是 1，再提交 0（旧版本）必须 409
        mockMvc.perform(patch(CUSTOMERS + "/" + id).cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content("{\"version\":0,\"note\":\"用旧版本号再改\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT_VERSION"));
    }

    @Test
    void patchAppendsChangeLogRowsWithReasonAdminAndRequestId() throws Exception {
        var created = objectMapper.readTree(postCustomer(body("客户测试-变更日志", "13800000008", false), key())
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("data");
        var id = created.get("id").asLong();
        var customerNo = created.get("customerNo").asText();

        mockMvc.perform(patch(CUSTOMERS + "/" + id).cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .header("X-Request-Id", "cust-req-patch-1")
                        .content("{\"version\":0,\"note\":\"第一版备注\",\"reason\":\"补充备注一\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.note").value("第一版备注"));

        mockMvc.perform(patch(CUSTOMERS + "/" + id).cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .header("X-Request-Id", "cust-req-patch-2")
                        .content("{\"version\":1,\"note\":\"第二版备注\",\"reason\":\"补充备注二\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.note").value("第二版备注"));

        var rows = jdbcTemplate.queryForList(
                "SELECT before_json, after_json, reason, admin_username, request_id FROM master_data_change_logs "
                        + "WHERE entity_type = 'CUSTOMER' AND business_no = ? ORDER BY id", customerNo);
        assertThat(rows).as("两次编辑必须追加两行互不覆盖").hasSize(2);

        var firstBefore = objectMapper.readTree((String) rows.get(0).get("before_json"));
        var firstAfter = objectMapper.readTree((String) rows.get(0).get("after_json"));
        assertThat(firstBefore.get("note").asText()).isEqualTo("原始备注");
        assertThat(firstAfter.get("note").asText()).isEqualTo("第一版备注");
        assertThat(rows.get(0).get("reason")).isEqualTo("补充备注一");
        assertThat(rows.get(0).get("admin_username")).isEqualTo(USERNAME);
        assertThat(rows.get(0).get("request_id")).isEqualTo("cust-req-patch-1");

        var secondBefore = objectMapper.readTree((String) rows.get(1).get("before_json"));
        var secondAfter = objectMapper.readTree((String) rows.get(1).get("after_json"));
        assertThat(secondBefore.get("note").asText()).isEqualTo("第一版备注");
        assertThat(secondAfter.get("note").asText()).isEqualTo("第二版备注");
        assertThat(rows.get(1).get("reason")).isEqualTo("补充备注二");
        assertThat(rows.get(1).get("admin_username")).isEqualTo(USERNAME);
        assertThat(rows.get(1).get("request_id")).isEqualTo("cust-req-patch-2");
    }

    @Test
    void listFiltersByNameAndPhoneAndDetailReturnsCustomer() throws Exception {
        var name = "客户测试-列表甲";
        var created = objectMapper.readTree(postCustomer(body(name, "13800000009", false), key())
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("data");
        var id = created.get("id").asLong();
        var customerNo = created.get("customerNo").asText();

        mockMvc.perform(get(CUSTOMERS).cookie(sessionCookie).param("name", name))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].customerNo").value(customerNo));

        mockMvc.perform(get(CUSTOMERS).cookie(sessionCookie).param("phone", "13800000009"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].customerNo").value(customerNo));

        mockMvc.perform(get(CUSTOMERS).cookie(sessionCookie).param("name", "客户测试-查无此人"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(get(CUSTOMERS + "/" + id).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerNo").value(customerNo))
                .andExpect(jsonPath("$.data.name").value(name));
    }
}

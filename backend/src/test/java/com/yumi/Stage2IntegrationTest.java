package com.yumi;

import com.yumi.shared.numbering.SequenceAllocator;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 2.11 阶段二集成测试：唯一编号并发、四位精度落库、跨域 HTTP 流程
 * （重复提示→确认独立建行、停用商品仍可编辑、离职资格拒绝、文件鉴权）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class Stage2IntegrationTest {

    private static final String USERNAME = "stage2-int-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    SequenceAllocator allocator;

    @Autowired
    TransactionTemplate transactionTemplate;

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
    }

    @Test
    void concurrentSequenceAllocationNeverDuplicates() throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        try {
            var futures = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(i -> executor.submit(() ->
                            transactionTemplate.execute(status -> SequenceAllocator.format("P", allocator.next("products")))))
                    .toList();
            var values = new HashSet<String>();
            for (var future : futures) {
                var no = future.get();
                assertThat(values.add(no)).as("并发分配不得重复编号 %s", no).isTrue();
            }
            assertThat(values).hasSize(20);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void fourDecimalMoneyStoredWithExactScaleInDatabase() throws Exception {
        var unique = "ITG-" + System.nanoTime();
        var create = mockMvc.perform(post("/api/products")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "itg-price-" + System.nanoTime())
                        .content("""
                                {"name":"%s","starLevelId":3,"moldQuantity":10,"dailyBatchLimit":5,"salePrice":"19.9999","weightG":100,
                                 "lossRatePercent":"0","seamMinutes":"0","seamDefaultFee":"0"}
                                """.formatted(unique)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andReturn();
        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(create.getResponse().getContentAsString());
        var productNo = body.path("data").path("productNo").asText();

        var row = jdbcTemplate.queryForMap(
                "SELECT sale_price, total_cost, loss_rate FROM products WHERE product_no = ?", productNo);
        assertThat(row.get("sale_price").toString()).isEqualTo("19.9999");
        var scale = jdbcTemplate.queryForObject(
                "SELECT numeric_scale FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND table_name = 'products' AND column_name = 'sale_price'",
                Integer.class);
        assertThat(scale).isEqualTo(4);

        var fetched = mockMvc.perform(get("/api/products/" + body.path("data").path("id").asLong())
                        .cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.salePrice").value("19.9999"))
                .andReturn();
        assertThat(fetched.getResponse().getContentAsString()).doesNotContain("19.999900000000000");
    }

    @Test
    void crossDomainFlowDisableCustomerDupEmployeeLeaveFileAuth() throws Exception {
        // 商品：创建→停用→停用后仍可编辑（规格：停用商品允许编辑）
        var pNo = "ITG2-" + System.nanoTime();
        var product = mockMvc.perform(post("/api/products")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "itg-p-" + System.nanoTime())
                        .content("""
                                {"name":"%s","starLevelId":1,"moldQuantity":10,"dailyBatchLimit":5,"salePrice":"5.0000","weightG":10,
                                 "lossRatePercent":"0","seamMinutes":"0","seamDefaultFee":"0"}
                                """.formatted(pNo)))
                .andExpect(status().isCreated())
                .andReturn();
        var productId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(product.getResponse().getContentAsString()).path("data").path("id").asLong();
        var productVersion = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(product.getResponse().getContentAsString()).path("data").path("version").asInt();

        mockMvc.perform(post("/api/products/" + productId + "/disable")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "itg-d-" + System.nanoTime())
                        .content("{\"version\":" + productVersion + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        var afterDisable = jdbcTemplate.queryForObject(
                "SELECT version FROM products WHERE id = ?", Integer.class, productId);
        mockMvc.perform(patch("/api/products/" + productId)
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "itg-pe-" + System.nanoTime())
                        .content("""
                                {"version":%d,"note":"停用后仍可编辑"}
                                """.formatted(afterDisable)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        // 客户：重复提示不入库→确认独立建行
        var dupName = "重复客户-" + System.nanoTime();
        var customerJson = """
                {"name":"%s","defaultRecipient":"张三","defaultRecipientPhone":"13800000000",
                 "defaultRegion":"华东","defaultAddress":"某路1号"}
                """.formatted(dupName);
        var first = mockMvc.perform(post("/api/customers")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "itg-c1-" + System.nanoTime())
                        .content(customerJson))
                .andExpect(status().isCreated())
                .andReturn();
        var firstNo = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(first.getResponse().getContentAsString()).path("data").path("customerNo").asText();

        var hint = mockMvc.perform(post("/api/customers")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "itg-c2-" + System.nanoTime())
                        .content(customerJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value("false"))
                .andExpect(jsonPath("$.data.duplicateCandidates[0].customerNo").value(firstNo))
                .andReturn();
        assertThat(hint.getResponse().getContentAsString()).doesNotContain("\"created\":true");

        var confirmed = mockMvc.perform(post("/api/customers")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "itg-c3-" + System.nanoTime())
                        .content(customerJson.replace("}", ",\"duplicateConfirmed\":true}")))
                .andExpect(status().isCreated())
                .andReturn();
        var secondNo = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(confirmed.getResponse().getContentAsString()).path("data").path("customerNo").asText();
        assertThat(secondNo).isNotEqualTo(firstNo);

        // 员工：离职后资格 409 EMPLOYEE_NOT_ELIGIBLE
        var employee = mockMvc.perform(post("/api/employees")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "itg-e-" + System.nanoTime())
                        .content("""
                                {"name":"资格测试","firstHireDate":"2026-01-01","workTypes":["MAKING"]}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        var employeeId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(employee.getResponse().getContentAsString()).path("data").path("id").asLong();
        mockMvc.perform(post("/api/employees/" + employeeId + "/leave")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "itg-l-" + System.nanoTime())
                        .content("{\"reason\":\"集成测试离职\",\"date\":\"2026-09-24\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/employees/" + employeeId + "/eligibility")
                        .cookie(sessionCookie)
                        .param("workType", "MAKING"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMPLOYEE_NOT_ELIGIBLE"));

        // 文件鉴权：未登录 401、登录后 404（不存在 id）
        mockMvc.perform(get("/api/files/999999"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mockMvc.perform(get("/api/files/999999").cookie(sessionCookie))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}

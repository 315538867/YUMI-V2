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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 2.6 客户详情只读汇总骨架：summary 四字段结构、金额一律 "0.0000" 字符串零值、
 * 首期订单/收付款事实未接线（阶段 3/7 再计算）；详情响应 summary 之外无可写余额字段；未登录 401。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerSummaryTest {

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
    void login() throws Exception {
        var testOrders = "SELECT id FROM orders WHERE order_no LIKE 'TS9%'";
        jdbcTemplate.update("DELETE FROM refunds WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM payments WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM orders WHERE order_no LIKE 'TS9%'");
        jdbcTemplate.update("DELETE FROM customers WHERE name LIKE '客户测试-%'");
        jdbcTemplate.update("DELETE FROM idempotency_records WHERE idempotency_key LIKE 'cust-sum-key-%'");
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

    private long createCustomer() throws Exception {
        var response = mockMvc.perform(post(CUSTOMERS).cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "cust-sum-key-" + UUID.randomUUID())
                        .content("""
                                {"name":"客户测试-汇总甲","phone":"13800000010",
                                 "defaultRecipient":"收件人甲","defaultRecipientPhone":"13700000001",
                                 "defaultRegion":"浙江省杭州市余杭区","defaultAddress":"文一西路1号"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data").get("id").asLong();
    }

    @Test
    void detailCarriesReadOnlyZeroSummarySkeleton() throws Exception {
        var id = createCustomer();

        var body = mockMvc.perform(get(CUSTOMERS + "/" + id).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.summary.orderCount").value("0"))
                .andExpect(jsonPath("$.data.summary.totalOrdered").value("0.0000"))
                .andExpect(jsonPath("$.data.summary.totalReceived").value("0.0000"))
                .andExpect(jsonPath("$.data.summary.totalRefunded").value("0.0000"))
                .andReturn().getResponse().getContentAsString();

        var data = objectMapper.readTree(body).get("data");
        var summary = data.get("summary");
        assertThat(summary.isObject()).isTrue();
        assertThat(summary.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("orderCount", "totalOrdered", "totalReceived", "totalRefunded");
        assertThat(summary.get("orderCount").isTextual()).as("数量也是字符串").isTrue();
        assertThat(summary.get("totalOrdered").isTextual()).isTrue();
        assertThat(summary.get("totalReceived").isTextual()).isTrue();
        assertThat(summary.get("totalRefunded").isTextual()).isTrue();

        // 只读契约：summary 之外是客户本体字段，不存在任何余额/可写金额字段
        assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "id", "customerNo", "name", "contact", "phone", "note",
                "defaultRecipient", "defaultRecipientPhone", "defaultRegion", "defaultAddress",
                "version", "createdAt", "updatedAt", "summary");
        assertThat(data.fieldNames()).toIterable()
                .noneMatch(field -> field.toLowerCase().contains("balance")
                        || field.toLowerCase().contains("paid")
                        || field.toLowerCase().contains("received")
                        || field.toLowerCase().contains("refunded")
                        || field.toLowerCase().contains("ordered"));
    }

    /**
     * 任务 2.6 的「阶段 3/7 接线」：汇总必须由**订单与收退款事实**实时聚合，而不是骨架常量 0；
     * 草稿订单属于内部工作态，不计入客户台账。
     */
    @Test
    void detailAggregatesOrdersReceiptsAndRefundsFromFacts() throws Exception {
        long customerId = createCustomer();
        insertOrder(customerId, "TS90001", "CONFIRMED", "100.0000");
        insertOrder(customerId, "TS90002", "CONFIRMED", "50.0000");
        insertOrder(customerId, "TS90003", "DRAFT", "999.0000");
        jdbcTemplate.update("""
                INSERT INTO payments (payment_no, order_id, amount, business_date, method, operator_username,
                    version, created_at, updated_at)
                VALUES ('PA900001', (SELECT id FROM orders WHERE order_no = 'TS90001'), 40.0000, '2026-09-25',
                    'TRANSFER', 'tester', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        jdbcTemplate.update("""
                INSERT INTO refunds (refund_no, order_id, amount, business_date, method, reason, source_type,
                    source_id, operator_username, version, created_at, updated_at)
                VALUES ('RF900001', (SELECT id FROM orders WHERE order_no = 'TS90002'), 10.0000, '2026-09-25',
                    'TRANSFER', '验收-汇总', 'ORDER_CHANGE', 1, 'tester', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);

        mockMvc.perform(get(CUSTOMERS + "/" + customerId).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.orderCount").value("2"))
                .andExpect(jsonPath("$.data.summary.totalOrdered").value("150.0000"))
                .andExpect(jsonPath("$.data.summary.totalReceived").value("40.0000"))
                .andExpect(jsonPath("$.data.summary.totalRefunded").value("10.0000"));
    }

    private void insertOrder(long customerId, String orderNo, String status, String receivable) {
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, customer_id, customer_name, status, order_date, recipient_name,
                    recipient_phone, region, address, goods_amount, seam_amount, discount_amount,
                    receivable_amount, goods_cost_amount, seam_cost_amount, cost_amount, profit_amount,
                    version, created_at, updated_at)
                VALUES (?, ?, '客户测试-汇总甲', ?, '2026-09-25', '收件人甲', '13700000001',
                    '浙江省杭州市余杭区', '文一西路1号', 100.0000, 0.0000, 0.0000, ?, 60.0000, 0.0000, 60.0000,
                    40.0000, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderNo, customerId, status, receivable);
    }

    @Test
    void detailRequiresAuthentication() throws Exception {
        mockMvc.perform(get(CUSTOMERS + "/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }
}

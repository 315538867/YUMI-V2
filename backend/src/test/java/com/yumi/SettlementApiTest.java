package com.yumi;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 7.8：多次收款、退款上限、来源必填、变更待退款、草稿拒绝、生产完成但未交付拒绝关闭、终态互斥。
 * 结清口径见施工文档 §4：结清净额 = 累计收款 − 累计变更退款；售后退款只进累计实际净收。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SettlementApiTest {

    private static final String USERNAME = "settlement-admin";
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
    private long orderId;
    private long orderItemId;

    @BeforeEach
    void setup() throws Exception {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO admin_accounts (username, password_hash, status, version, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, USERNAME, passwordEncoder.encode(PASSWORD));
        jdbcTemplate.update("""
                INSERT INTO products (product_no, name, status, star_level_id, star_name, star_std_minutes,
                    sale_price, weight_g, total_cost, version, created_at, updated_at)
                VALUES ('TSL001', 'TST-结清商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 6.0000, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TSL001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, version, created_at, updated_at)
                VALUES ('TSL001', 'TST-结清客户', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TSL001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, customer_id, customer_name, status, order_date, recipient_name,
                    recipient_phone, region, address, goods_amount, seam_amount, discount_amount,
                    receivable_amount, goods_cost_amount, seam_cost_amount, cost_amount, profit_amount,
                    version, created_at, updated_at)
                VALUES ('TSL0001', ?, 'TST-结清客户', 'CONFIRMED', '2026-09-25', '收货人', '13800000000',
                    '华东', '地址', 100.0000, 0.0000, 0.0000, 100.0000, 60.0000, 0.0000, 60.0000, 40.0000,
                    1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, customerId);
        orderId = jdbcTemplate.queryForObject("SELECT id FROM orders WHERE order_no = 'TSL0001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO order_items (order_id, line_no, product_id, product_no, product_name,
                    quantity, seam_quantity, unit_price, goods_amount, unit_cost, goods_cost_amount,
                    version, created_at, updated_at)
                VALUES (?, 1, ?, 'TSL001', 'TST-结清商品', 10, 0, 10.0000, 100.0000, 6.0000, 60.0000,
                    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, productId);
        orderItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND line_no = 1", Long.class, orderId);
        jdbcTemplate.update("""
                INSERT INTO order_item_fulfillment_balances (order_id, order_item_id, required_quantity,
                    version, created_at, updated_at)
                VALUES (?, ?, 10, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, orderItemId);
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
    }

    @AfterEach
    void cleanup() {
        var testOrders = "SELECT id FROM orders WHERE order_no = 'TSL0001'";
        jdbcTemplate.update("DELETE FROM after_sales_cases WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_settlement_balances WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM refunds WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM payments WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_change_items WHERE change_order_id IN "
                + "(SELECT id FROM order_change_orders WHERE order_id IN (" + testOrders + "))");
        jdbcTemplate.update("DELETE FROM order_change_orders WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM fulfillment_entries WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_item_fulfillment_balances WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_item_snapshots WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_confirmation_snapshots WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_items WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM orders WHERE order_no = 'TSL0001'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TSL001'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TSL001'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    @Test
    void rejectsDraftPaymentAndTracksPartialThenFullPayment() throws Exception {
        jdbcTemplate.update("UPDATE orders SET status = 'DRAFT' WHERE id = ?", orderId);
        pay("40.0000")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_DRAFT_FORBIDDEN"));

        jdbcTemplate.update("UPDATE orders SET status = 'CONFIRMED' WHERE id = ?", orderId);
        pay("40.0000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paidAmount").value("40.0000"))
                .andExpect(jsonPath("$.data.netSettledAmount").value("40.0000"))
                .andExpect(jsonPath("$.data.receivableStatus").value("部分收款"))
                .andExpect(jsonPath("$.data.payments.length()").value(1));
        pay("60.0000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paidAmount").value("100.0000"))
                .andExpect(jsonPath("$.data.receivableStatus").value("已结清"))
                .andExpect(jsonPath("$.data.payments.length()").value(2));
        // 金额必须大于 0
        pay("0.0000")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='amount')]").isNotEmpty());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT paid_amount FROM order_settlement_balances WHERE order_id = ?", String.class, orderId))
                .isEqualTo("100.0000");
    }

    @Test
    void refundRequiresSourceAndRespectsReceiptCap() throws Exception {
        pay("100.0000").andExpect(status().isOk());
        // 售后退款必须关联真实售后单（阶段八守卫）：直接种一条最小售后单
        jdbcTemplate.update("""
                INSERT INTO after_sales_cases (case_no, order_id, case_type, status, problem, created_at,
                    updated_at)
                VALUES ('AS900001', ?, 'REWORK', 'OPEN', '验收售后', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId);
        long caseId = jdbcTemplate.queryForObject(
                "SELECT id FROM after_sales_cases WHERE case_no = 'AS900001'", Long.class);

        // 来源必填
        mockMvc.perform(post("/api/orders/" + orderId + "/refunds")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"30.0000\",\"businessDate\":\"2026-09-25\",\"method\":\"银行转账\","
                                + "\"reason\":\"客户要求\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='sourceType')]").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='sourceId')]").isNotEmpty());

        // 订单变更退款必须关联本单的变更单
        mockMvc.perform(post("/api/orders/" + orderId + "/refunds")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"30.0000\",\"businessDate\":\"2026-09-25\",\"method\":\"银行转账\","
                                + "\"reason\":\"减单退款\",\"sourceType\":\"ORDER_CHANGE\",\"sourceId\":999999}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_REFERENCE_REQUIRED"));

        // 退款累计不得超过累计收款
        mockMvc.perform(post("/api/orders/" + orderId + "/refunds")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"120.0000\",\"businessDate\":\"2026-09-25\",\"method\":\"银行转账\","
                                + "\"reason\":\"超出收款\",\"sourceType\":\"AFTER_SALES\",\"sourceId\":" + caseId + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_EXCEEDS_RECEIPTS"));

        // 售后退款：不冲减订单结清净额，只进累计实际净收
        mockMvc.perform(post("/api/orders/" + orderId + "/refunds")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"30.0000\",\"businessDate\":\"2026-09-25\",\"method\":\"银行转账\","
                                + "\"reason\":\"售后退款\",\"sourceType\":\"AFTER_SALES\",\"sourceId\":" + caseId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.netSettledAmount").value("100.0000"))
                .andExpect(jsonPath("$.data.afterSalesRefundAmount").value("30.0000"))
                .andExpect(jsonPath("$.data.actualNetReceived").value("70.0000"))
                .andExpect(jsonPath("$.data.refundPendingAmount").value("0.0000"))
                .andExpect(jsonPath("$.data.receivableStatus").value("已结清"));
    }

    @Test
    void changeRefundCreatesPendingRefundAndBlocksClose() throws Exception {
        pay("100.0000").andExpect(status().isOk());
        // 减单：数量 10 → 5，应收 100 → 50
        var changeCreated = mockMvc.perform(post("/api/orders/" + orderId + "/change-orders")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"客户减单","items":[{"orderItemId":%d,"quantity":5,"seamQuantity":0,
                                 "unitPrice":"10.0000"}]}
                                """.formatted(orderItemId)))
                .andExpect(status().isCreated())
                .andReturn();
        long changeId = objectMapper.readTree(changeCreated.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        mockMvc.perform(post("/api/order-changes/" + changeId + "/confirm")
                        .cookie(sessionCookie).header("Idempotency-Key", key()))
                .andExpect(status().isOk());

        // 该明细已发满（需求已随变更降为 5），只剩款项条件
        jdbcTemplate.update("UPDATE order_item_fulfillment_balances SET shipped_quantity = 5, "
                + "shippable_quantity = 0, required_quantity = 5 WHERE order_item_id = ?", orderItemId);
        // 收款 100 > 当前有效应收 50 且未退变更款 → 待退款 50，关闭被阻止
        mockMvc.perform(get("/api/orders/" + orderId + "/settlement").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveReceivableAmount").value("50.0000"))
                .andExpect(jsonPath("$.data.refundPendingAmount").value("50.0000"))
                .andExpect(jsonPath("$.data.receivableStatus").value("待退款"));
        close()
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLOSE_REFUND_PENDING"));

        // 登记关联变更单的退款 50 → 待退款归零，但仍有未交付需求 → 关闭被履约阻止
        mockMvc.perform(post("/api/orders/" + orderId + "/refunds")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"50.0000\",\"businessDate\":\"2026-09-25\",\"method\":\"银行转账\","
                                + "\"reason\":\"减单退款\",\"sourceType\":\"ORDER_CHANGE\",\"sourceId\":" + changeId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refundPendingAmount").value("0.0000"))
                .andExpect(jsonPath("$.data.netSettledAmount").value("50.0000"));
        // 退款处理完后三项条件全部满足 → 关闭成功
        close()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderStatus").value("CLOSED"));
    }

    @Test
    void productionCompletionDoesNotReplaceDelivery() throws Exception {
        // 生产已全部处理并有成品余量，但客户需求一件都没发
        jdbcTemplate.update("UPDATE order_item_fulfillment_balances SET verified_processed = 10, "
                + "shippable_quantity = 10, finished_surplus_quantity = 2 WHERE order_item_id = ?", orderItemId);
        pay("100.0000").andExpect(status().isOk());

        close()
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLOSE_FULFILLMENT_PENDING"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='fulfillment')]").isNotEmpty());
        mockMvc.perform(get("/api/orders/" + orderId + "/settlement").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.closeConditions[0].satisfied").value(false))
                .andExpect(jsonPath("$.data.closeConditions[1].satisfied").value(true))
                .andExpect(jsonPath("$.data.closeConditions[2].satisfied").value(true));
    }

    @Test
    void closeRequiresSettlementAndIsTerminal() throws Exception {
        // 已全部发货但未收款 → 应收未结清
        jdbcTemplate.update("UPDATE order_item_fulfillment_balances SET shipped_quantity = 10, "
                + "shippable_quantity = 0 WHERE order_item_id = ?", orderItemId);
        close()
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLOSE_SETTLEMENT_PENDING"));

        pay("100.0000").andExpect(status().isOk());
        close()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderStatus").value("CLOSED"))
                .andExpect(jsonPath("$.data.receivableStatus").value("已关闭"));

        // 已关闭是终态：不得重开，也不得新增收款
        close()
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_NOT_CANCELABLE"));
        pay("10.0000")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_NOT_EDITABLE"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId)).isEqualTo("CLOSED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT closed_by FROM orders WHERE id = ?", String.class, orderId)).isEqualTo(USERNAME);
    }

    // ---------- 工具 ----------

    private org.springframework.test.web.servlet.ResultActions pay(String amount) throws Exception {
        return mockMvc.perform(post("/api/orders/" + orderId + "/payments")
                .cookie(sessionCookie).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount":"%s","businessDate":"2026-09-25","method":"微信","note":"验收收款"}
                        """.formatted(amount)));
    }

    private org.springframework.test.web.servlet.ResultActions close() throws Exception {
        return mockMvc.perform(post("/api/orders/" + orderId + "/close")
                .cookie(sessionCookie).header("Idempotency-Key", key()));
    }

    private String key() {
        return java.util.UUID.randomUUID().toString();
    }
}

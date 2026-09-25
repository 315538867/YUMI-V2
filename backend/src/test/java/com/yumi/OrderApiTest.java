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
 * 任务 3.2 订单草稿 API：编号、服务端金额、明细解析、收货与缝边默认值、校验、草稿编辑与替换明细、
 * 非草稿不可编辑、版本冲突与列表筛选。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderApiTest {

    private static final String USERNAME = "order-api-admin";
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
    private long customerId;
    private long productId;
    private long seamTypeId;

    @BeforeEach
    void setup() throws Exception {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO admin_accounts (username, password_hash, status, version, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, USERNAME, passwordEncoder.encode(PASSWORD));
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, contact, phone, default_recipient,
                    default_recipient_phone, default_region, default_address, version, created_at, updated_at)
                VALUES ('TC0002', 'TST-订单客户', '联系人', '13900000000', '默认收货人', '13900000001',
                    '华南', '默认收货地址', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TC0002'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO seam_types (name, std_minutes, version, created_at, updated_at)
                VALUES ('TST-订单缝边种类', 5, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        seamTypeId = jdbcTemplate.queryForObject(
                "SELECT id FROM seam_types WHERE name = 'TST-订单缝边种类'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO products (product_no, name, status, star_level_id, star_name, star_std_minutes,
                    sale_price, weight_g, total_cost, reference_price, seam_type_id, seam_type_name,
                    seam_std_minutes, seam_unit_cost, seam_fee, version, created_at, updated_at)
                VALUES ('TP0002', 'TST-订单商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    25.0000, 100, 18.1200, 25.8857, ?, 'TST-订单缝边种类', 5, 1.2500, 2.0000, 0,
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, seamTypeId);
        productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TP0002'", Long.class);
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
    }

    @AfterEach
    void cleanup() {
        var testOrders = "SELECT id FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TC0002')";
        jdbcTemplate.update("DELETE FROM order_change_items WHERE change_order_id IN "
                + "(SELECT id FROM order_change_orders WHERE order_id IN (" + testOrders + "))");
        for (var table : new String[]{"fulfillment_entries", "order_item_fulfillment_balances",
                "order_item_snapshots", "order_confirmation_snapshots", "order_change_orders", "order_items"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id IN (" + testOrders + ")");
        }
        jdbcTemplate.update("DELETE FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TC0002')");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TP0002'");
        jdbcTemplate.update("DELETE FROM seam_types WHERE name = 'TST-订单缝边种类'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TC0002'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    @Test
    void createsDraftWithMonotonicNumberAndServerSideAmounts() throws Exception {
        var data = createDraft();

        assertThat(data.path("orderNo").asText()).matches("YM\\d{5}");
        assertThat(data.path("status").asText()).isEqualTo("DRAFT");
        assertThat(data.path("version").asLong()).isZero();
        // 明细：订购数量=10、缝边数量=4；商品金额 250.0000、缝边收费 8.0000、商品成本 181.2000、缝边成本 5.0000
        var item = data.path("items").get(0);
        assertThat(item.path("quantity").asInt()).isEqualTo(10);
        assertThat(item.path("seamQuantity").asInt()).isEqualTo(4);
        assertThat(item.path("unitPrice").asText()).isEqualTo("25.0000");
        assertThat(item.path("goodsAmount").asText()).isEqualTo("250.0000");
        assertThat(item.path("seamAmount").asText()).isEqualTo("8.0000");
        assertThat(item.path("unitCost").asText()).isEqualTo("18.1200");
        assertThat(item.path("goodsCostAmount").asText()).isEqualTo("181.2000");
        assertThat(item.path("seamCostAmount").asText()).isEqualTo("5.0000");
        // 订单：应收 250+8−10=248.0000；成本 186.2000；利润 61.8000
        assertThat(data.path("goodsAmount").asText()).isEqualTo("250.0000");
        assertThat(data.path("seamAmount").asText()).isEqualTo("8.0000");
        assertThat(data.path("discountAmount").asText()).isEqualTo("10.0000");
        assertThat(data.path("receivableAmount").asText()).isEqualTo("248.0000");
        assertThat(data.path("goodsCostAmount").asText()).isEqualTo("181.2000");
        assertThat(data.path("seamCostAmount").asText()).isEqualTo("5.0000");
        assertThat(data.path("costAmount").asText()).isEqualTo("186.2000");
        assertThat(data.path("profitAmount").asText()).isEqualTo("61.8000");

        var row = jdbcTemplate.queryForMap("""
                SELECT o.status, o.receivable_amount, o.cost_amount, i.line_no, i.seam_type_id, i.seam_fee
                FROM orders o JOIN order_items i ON i.order_id = o.id WHERE o.order_no = ?
                """, data.path("orderNo").asText());
        assertThat(String.valueOf(row.get("receivable_amount"))).isEqualTo("248.0000");
        assertThat(String.valueOf(row.get("cost_amount"))).isEqualTo("186.2000");
        assertThat(((Number) row.get("line_no")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("seam_type_id")).longValue()).isEqualTo(seamTypeId);
        assertThat(String.valueOf(row.get("seam_fee"))).isEqualTo("2.0000");
    }

    @Test
    void defaultsRecipientAndSeamFromCustomerAndProduct() throws Exception {
        // 不传收货信息与缝边种类/收费：收货取客户默认，缝边种类与收费取商品默认
        var data = createDraft();

        assertThat(data.path("recipientName").asText()).isEqualTo("默认收货人");
        assertThat(data.path("recipientPhone").asText()).isEqualTo("13900000001");
        assertThat(data.path("region").asText()).isEqualTo("华南");
        assertThat(data.path("address").asText()).isEqualTo("默认收货地址");
        assertThat(data.path("items").get(0).path("seamTypeName").asText()).isEqualTo("TST-订单缝边种类");
        assertThat(data.path("items").get(0).path("seamUnitCost").asText()).isEqualTo("1.2500");
        assertThat(data.path("items").get(0).path("seamFee").asText()).isEqualTo("2.0000");

        // 不缝边剪袋（缝边数量=0）：缝边字段一律为空或 0
        var noSeam = createDraft("""
                {"customerId":%d,"orderDate":"2026-09-24","items":[{"productId":%d,"quantity":3,"seamQuantity":0}]}
                """.formatted(customerId, productId));
        assertThat(noSeam.path("items").get(0).path("seamTypeId").isNull()).isTrue();
        assertThat(noSeam.path("items").get(0).path("seamAmount").asText()).isEqualTo("0.0000");
        assertThat(noSeam.path("items").get(0).path("seamCostAmount").asText()).isEqualTo("0.0000");
        assertThat(noSeam.path("seamAmount").asText()).isEqualTo("0.0000");
    }

    @Test
    void rejectsInvalidItemsDeliveryAndDiscount() throws Exception {
        // 数量为 0
        expectValidation("""
                {"customerId":%d,"orderDate":"2026-09-24","items":[{"productId":%d,"quantity":0}]}
                """.formatted(customerId, productId), "items[0].quantity");
        // 缝边数量 > 订购数量
        expectValidation("""
                {"customerId":%d,"orderDate":"2026-09-24","items":[{"productId":%d,"quantity":2,"seamQuantity":3}]}
                """.formatted(customerId, productId), "items[0].seamQuantity");
        // 商品不存在
        expectValidation("""
                {"customerId":%d,"orderDate":"2026-09-24","items":[{"productId":999999,"quantity":1}]}
                """.formatted(customerId), "items[0].productId");
        // 明细为空
        expectValidation("""
                {"customerId":%d,"orderDate":"2026-09-24","items":[]}
                """.formatted(customerId), "items");
        // 客户不存在
        expectValidation("""
                {"customerId":999999,"orderDate":"2026-09-24","items":[{"productId":%d,"quantity":1}]}
                """.formatted(productId), "customerId");
        // 交期早于下单日期
        expectValidation("""
                {"customerId":%d,"orderDate":"2026-09-24","expectedDeliveryDate":"2026-09-23",
                 "items":[{"productId":%d,"quantity":1}]}
                """.formatted(customerId, productId), "expectedDeliveryDate");
        // 优惠超过商品金额 + 缝边收费
        expectValidation("""
                {"customerId":%d,"orderDate":"2026-09-24","discountAmount":"1000.0000",
                 "items":[{"productId":%d,"quantity":1,"unitPrice":"10.0000"}]}
                """.formatted(customerId, productId), "discountAmount");

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders WHERE customer_id = ?",
                Integer.class, customerId)).as("校验失败不得落库").isZero();
    }

    @Test
    void rejectsDisabledProduct() throws Exception {
        jdbcTemplate.update("UPDATE products SET status = 'DISABLED' WHERE id = ?", productId);
        expectValidation("""
                {"customerId":%d,"orderDate":"2026-09-24","items":[{"productId":%d,"quantity":1}]}
                """.formatted(customerId, productId), "items[0].productId");
    }

    @Test
    void updatesDraftReplacingItemsAndRecomputingAmounts() throws Exception {
        var created = createDraft();
        var id = created.path("id").asLong();

        var updated = mockMvc.perform(patch("/api/orders/" + id)
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":0,"note":"改备注","discountAmount":"0.0000",
                                 "items":[{"productId":%d,"quantity":2,"seamQuantity":0,"unitPrice":"10.0000"},
                                          {"productId":%d,"quantity":1,"seamQuantity":1,"seamFee":"3.0000"}]}
                                """.formatted(productId, productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.note").value("改备注"))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andReturn();
        var data = objectMapper.readTree(updated.getResponse().getContentAsString()).path("data");

        assertThat(data.path("items").get(0).path("lineNo").asInt()).isEqualTo(1);
        assertThat(data.path("items").get(1).path("lineNo").asInt()).isEqualTo(2);
        // 商品金额 10×2 + 25×1 = 45.0000；缝边收费 3.0000×1 = 3.0000；应收 48.0000
        assertThat(data.path("goodsAmount").asText()).isEqualTo("45.0000");
        assertThat(data.path("seamAmount").asText()).isEqualTo("3.0000");
        assertThat(data.path("discountAmount").asText()).isEqualTo("0.0000");
        assertThat(data.path("receivableAmount").asText()).isEqualTo("48.0000");
        // 商品成本 18.12×3 = 54.3600；缝边成本 1.2500；总成本 55.6100；利润 −7.6100
        assertThat(data.path("costAmount").asText()).isEqualTo("55.6100");
        assertThat(data.path("profitAmount").asText()).isEqualTo("-7.6100");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_items WHERE order_id = ?", Integer.class, id)).isEqualTo(2);

        // 不传 items：明细保持不变
        var headerOnly = mockMvc.perform(patch("/api/orders/" + id)
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"expectedDeliveryDate\":\"2026-10-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expectedDeliveryDate").value("2026-10-01"))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andReturn();
        assertThat(objectMapper.readTree(headerOnly.getResponse().getContentAsString())
                .path("data").path("receivableAmount").asText()).isEqualTo("48.0000");
    }

    @Test
    void rejectsEditWhenNotDraftAndOnVersionConflict() throws Exception {
        var created = createDraft();
        var id = created.path("id").asLong();

        mockMvc.perform(patch("/api/orders/" + id)
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":9,\"note\":\"旧版本\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT_VERSION"));

        jdbcTemplate.update("UPDATE orders SET status = 'CONFIRMED' WHERE id = ?", id);
        mockMvc.perform(patch("/api/orders/" + id)
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"note\":\"已确认后不可编辑\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_NOT_EDITABLE"));
    }

    @Test
    void listsOrdersWithFilters() throws Exception {
        var first = createDraft();
        createDraft("""
                {"customerId":%d,"orderDate":"2026-08-01","items":[{"productId":%d,"quantity":1}]}
                """.formatted(customerId, productId));

        mockMvc.perform(get("/api/orders").cookie(sessionCookie)
                        .param("customerId", String.valueOf(customerId))
                        .param("status", "DRAFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(get("/api/orders").cookie(sessionCookie)
                        .param("customerId", String.valueOf(customerId))
                        .param("orderDateFrom", "2026-09-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].orderNo").value(first.path("orderNo").asText()));

        mockMvc.perform(get("/api/orders/" + first.path("id").asLong()).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNo").value(first.path("orderNo").asText()));

        mockMvc.perform(get("/api/orders/999999").cookie(sessionCookie))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    // ---------- 工具 ----------

    private JsonNode createDraft() throws Exception {
        return createDraft("""
                {"customerId":%d,"orderDate":"2026-09-24","discountAmount":"10.0000",
                 "items":[{"productId":%d,"quantity":10,"seamQuantity":4}]}
                """.formatted(customerId, productId));
    }

    private JsonNode createDraft(String body) throws Exception {
        var created = mockMvc.perform(post("/api/orders")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).path("data");
    }

    private void expectValidation(String body, String field) throws Exception {
        mockMvc.perform(post("/api/orders")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='" + field + "')]").isNotEmpty());
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}

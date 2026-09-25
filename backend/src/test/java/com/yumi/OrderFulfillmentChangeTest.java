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
 * 任务 3.6–3.10：共同数量与履约视图、订单变更草稿与确认、减单不变量、订单取消与多维只读状态。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderFulfillmentChangeTest {

    private static final String USERNAME = "order-change-admin";
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

    @BeforeEach
    void setup() throws Exception {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO admin_accounts (username, password_hash, status, version, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, USERNAME, passwordEncoder.encode(PASSWORD));
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, default_recipient, default_recipient_phone,
                    default_region, default_address, version, created_at, updated_at)
                VALUES ('TC0004', 'TST-变更客户', '默认收货人', '13900000001', '华南', '默认收货地址', 0,
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TC0004'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO seam_types (name, std_minutes, version, created_at, updated_at)
                VALUES ('TST-变更缝边种类', 5, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        jdbcTemplate.update("""
                INSERT INTO products (product_no, name, status, star_level_id, star_name, star_std_minutes,
                    sale_price, weight_g, total_cost, seam_type_id, seam_type_name, seam_std_minutes,
                    seam_unit_cost, seam_fee, version, created_at, updated_at)
                VALUES ('TP0004', 'TST-变更商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    25.0000, 100, 18.1200, (SELECT id FROM seam_types WHERE name = 'TST-变更缝边种类'),
                    'TST-变更缝边种类', 5, 1.2500, 2.0000, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TP0004'", Long.class);
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
                + "(SELECT id FROM customers WHERE customer_no = 'TC0004')";
        jdbcTemplate.update("DELETE FROM order_change_items WHERE change_order_id IN "
                + "(SELECT id FROM order_change_orders WHERE order_id IN (" + testOrders + "))");
        for (var table : new String[]{"fulfillment_entries", "order_item_fulfillment_balances",
                "order_item_snapshots", "order_confirmation_snapshots", "order_change_orders", "order_items"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id IN (" + testOrders + ")");
        }
        jdbcTemplate.update("DELETE FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TC0004')");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TP0004'");
        jdbcTemplate.update("DELETE FROM seam_types WHERE name = 'TST-变更缝边种类'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TC0004'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    // ---------- 3.6 共同数量与履约视图 ----------

    @Test
    void fulfillmentSplitsSharedQuantityWithoutAddingProcesses() throws Exception {
        var order = confirmedOrder(10, 4);

        var view = mockMvc.perform(get("/api/orders/" + order.path("id").asLong() + "/fulfillment")
                        .cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].quantity").value(10))
                .andExpect(jsonPath("$.data.items[0].seamQuantity").value(4))
                .andExpect(jsonPath("$.data.items[0].noSeamRequired").value(6))
                .andExpect(jsonPath("$.data.items[0].makingRequired").value(10))
                .andExpect(jsonPath("$.data.items[0].packingRequired").value(10))
                .andExpect(jsonPath("$.data.items[0].seamRequired").value(4))
                // 最终交付需求是订购数量，不是订购数量+订购数量+缝边数量
                .andExpect(jsonPath("$.data.items[0].finalRequired").value(10))
                .andExpect(jsonPath("$.data.items[0].undelivered").value(10))
                .andExpect(jsonPath("$.data.items[0].makingInflow").value(0))
                .andExpect(jsonPath("$.data.items[0].shippable").value(0))
                .andExpect(jsonPath("$.data.items[0].shipped").value(0))
                .andReturn();
        var data = objectMapper.readTree(view.getResponse().getContentAsString()).path("data");
        int making = data.path("items").get(0).path("makingRequired").asInt();
        int packing = data.path("items").get(0).path("packingRequired").asInt();
        int seam = data.path("items").get(0).path("seamRequired").asInt();
        assertThat(data.path("items").get(0).path("finalRequired").asInt())
                .as("工序数量不得相加为订单数量").isNotEqualTo(making + packing + seam);

        // 阶段三无生产/发货事实：派生状态按事实判定，不用 0 伪造进度
        assertThat(data.path("derived").path("scheduling").asText()).isEqualTo("未排产");
        assertThat(data.path("derived").path("execution").asText()).isEqualTo("等待上游");
        assertThat(data.path("derived").path("production").asText()).isEqualTo("未开始");
        assertThat(data.path("derived").path("demandHandling").asText()).isEqualTo("仍有待履约");
        assertThat(data.path("derived").path("shipment").asText()).isEqualTo("未发货");
    }

    // ---------- 3.10 多维状态不因生产/余量自动完成需求 ----------

    @Test
    void productionProgressDoesNotCompleteCustomerDemand() throws Exception {
        var order = confirmedOrder(10, 0);
        long orderId = order.path("id").asLong();
        // 模拟阶段五写入：已排产 + 部分核验，但没有任何发货
        jdbcTemplate.update("""
                UPDATE order_item_fulfillment_balances SET making_inflow = 10, verified_processed = 4,
                    making_planned = 2 WHERE order_id = ?
                """, orderId);

        var data = mockMvc.perform(get("/api/orders/" + orderId + "/fulfillment").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andReturn();
        var derived = objectMapper.readTree(data.getResponse().getContentAsString())
                .path("data").path("derived");
        assertThat(derived.path("scheduling").asText()).isEqualTo("已排产");
        assertThat(derived.path("production").asText()).isEqualTo("部分完成");
        assertThat(derived.path("demandHandling").asText())
                .as("生产进度不自动完成客户需求").isEqualTo("仍有待履约");
        assertThat(derived.path("shipment").asText()).isEqualTo("未发货");

        // 成品余量同样不改变客户需求口径
        jdbcTemplate.update("""
                UPDATE order_item_fulfillment_balances SET finished_surplus_quantity = 3 WHERE order_id = ?
                """, orderId);
        var afterSurplus = objectMapper.readTree(mockMvc.perform(
                        get("/api/orders/" + orderId + "/fulfillment").cookie(sessionCookie))
                .andReturn().getResponse().getContentAsString()).path("data").path("derived");
        assertThat(afterSurplus.path("demandHandling").asText()).isEqualTo("仍有待履约");
        assertThat(afterSurplus.path("shipment").asText()).isEqualTo("未发货");

        // 订单详情与列表同样带派生状态
        mockMvc.perform(get("/api/orders/" + orderId).cookie(sessionCookie))
                .andExpect(jsonPath("$.data.derived.production").value("部分完成"))
                .andExpect(jsonPath("$.data.derived.demandHandling").value("仍有待履约"));
        mockMvc.perform(get("/api/orders").cookie(sessionCookie).param("customerId", String.valueOf(customerId)))
                .andExpect(jsonPath("$.data[0].derived.shipment").value("未发货"));
    }

    // ---------- 3.7 变更草稿与确认 ----------

    @Test
    void changeDraftKeepsStructuredBeforeAndAfterValues() throws Exception {
        var order = confirmedOrder(10, 4);
        long orderId = order.path("id").asLong();
        long firstItemId = itemId(orderId, 1);

        var draft = createChange(orderId, """
                {"reason":"客户调整","expectedDeliveryDate":"2026-10-15","note":"改备注","discountAmount":"5.0000",
                 "items":[{"orderItemId":%d,"quantity":8,"seamQuantity":2},
                          {"productId":%d,"quantity":3,"unitPrice":"20.0000"}]}
                """.formatted(firstItemId, productId));
        assertThat(draft.path("changeNo").asText()).matches("CO\\d{5}");
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.path("items").size()).isEqualTo(2);
        var update = draft.path("items").get(0);
        assertThat(update.path("changeType").asText()).isEqualTo("UPDATE");
        assertThat(update.path("beforeQuantity").asInt()).isEqualTo(10);
        assertThat(update.path("afterQuantity").asInt()).isEqualTo(8);
        assertThat(update.path("beforeSeamQuantity").asInt()).isEqualTo(4);
        assertThat(update.path("afterSeamQuantity").asInt()).isEqualTo(2);
        assertThat(update.path("beforeUnitPrice").asText()).isEqualTo("25.0000");
        assertThat(update.path("afterUnitPrice").asText()).isEqualTo("25.0000");
        var added = draft.path("items").get(1);
        assertThat(added.path("changeType").asText()).isEqualTo("ADD");
        assertThat(added.path("orderItemId").isNull()).isTrue();
        assertThat(added.path("afterQuantity").asInt()).isEqualTo(3);

        // 同一订单同时只允许一个变更草稿
        mockMvc.perform(post("/api/orders/" + orderId + "/change-orders")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"重复\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_NOT_CHANGEABLE"));

        // 草稿阶段订单本身未被改动
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM order_items WHERE id = ?", Integer.class, firstItemId)).isEqualTo(10);
    }

    @Test
    void changeConfirmationAppliesItemsFactsAndAmounts() throws Exception {
        var order = confirmedOrder(10, 4);
        long orderId = order.path("id").asLong();
        long firstItemId = itemId(orderId, 1);
        var draft = createChange(orderId, """
                {"reason":"客户调整","discountAmount":"5.0000",
                 "items":[{"orderItemId":%d,"quantity":8,"seamQuantity":2},
                          {"productId":%d,"quantity":3,"unitPrice":"20.0000"}]}
                """.formatted(firstItemId, productId));

        var result = mockMvc.perform(post("/api/order-changes/" + draft.path("id").asLong() + "/confirm")
                        .cookie(sessionCookie).header("Idempotency-Key", key()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.change.status").value("CONFIRMED"))
                .andReturn();
        var data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");

        // 明细：原行改为 8 件，新增行 3 件（lineNo=2）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM order_items WHERE id = ?", Integer.class, firstItemId)).isEqualTo(8);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT seam_quantity FROM order_items WHERE id = ?", Integer.class, firstItemId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_items WHERE order_id = ?", Integer.class, orderId)).isEqualTo(2);
        // 金额：商品金额 25×8 + 20×3 = 260.0000；缝边收费 2×2 = 4.0000；应收 264−5 = 259.0000
        assertThat(data.path("order").path("goodsAmount").asText()).isEqualTo("260.0000");
        assertThat(data.path("order").path("seamAmount").asText()).isEqualTo("4.0000");
        assertThat(data.path("order").path("receivableAmount").asText()).isEqualTo("259.0000");
        // 履约事实：减单 2 件（OUT）+ 新增 3 件（IN）
        var changeFacts = jdbcTemplate.queryForList("""
                SELECT direction, quantity FROM fulfillment_entries
                WHERE order_id = ? AND entry_type = 'ORDER_CHANGE' ORDER BY id
                """, orderId);
        assertThat(changeFacts).hasSize(2);
        assertThat(changeFacts.get(0).get("direction")).isEqualTo("OUT");
        assertThat(((Number) changeFacts.get(0).get("quantity")).intValue()).isEqualTo(2);
        assertThat(changeFacts.get(1).get("direction")).isEqualTo("IN");
        assertThat(((Number) changeFacts.get(1).get("quantity")).intValue()).isEqualTo(3);
        // 需求投影同步
        assertThat(jdbcTemplate.queryForObject(
                "SELECT required_quantity FROM order_item_fulfillment_balances WHERE order_item_id = ?",
                Integer.class, firstItemId)).isEqualTo(8);
        // 订单主状态不变
        assertThat(data.path("order").path("status").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    void changeRejectsDraftOrdersAndNonDraftChanges() throws Exception {
        var draftOrder = createDraft();
        mockMvc.perform(post("/api/orders/" + draftOrder.path("id").asLong() + "/change-orders")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"草稿不可变更\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_NOT_CHANGEABLE"));

        var order = confirmedOrder(10, 0);
        var change = createChange(order.path("id").asLong(), "{\"reason\":\"表头变更\"}");
        mockMvc.perform(post("/api/order-changes/" + change.path("id").asLong() + "/confirm")
                        .cookie(sessionCookie).header("Idempotency-Key", key()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/order-changes/" + change.path("id").asLong() + "/confirm")
                        .cookie(sessionCookie).header("Idempotency-Key", key()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_NOT_CHANGEABLE"));
    }

    // ---------- 3.8 减单不变量 ----------

    @Test
    void rejectsReductionBelowShipped() throws Exception {
        var order = confirmedOrder(10, 0);
        long orderId = order.path("id").asLong();
        long firstItemId = itemId(orderId, 1);
        jdbcTemplate.update(
                "UPDATE order_item_fulfillment_balances SET shipped_quantity = 7 WHERE order_item_id = ?",
                firstItemId);
        var change = createChange(orderId, """
                {"reason":"减单","items":[{"orderItemId":%d,"quantity":5}]}
                """.formatted(firstItemId));

        mockMvc.perform(post("/api/order-changes/" + change.path("id").asLong() + "/confirm")
                        .cookie(sessionCookie).header("Idempotency-Key", key()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUANTITY_BELOW_SHIPPED"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM order_items WHERE id = ?", Integer.class, firstItemId)).isEqualTo(10);
    }

    @Test
    void requiresDispositionWhenReductionExceedsInProcess() throws Exception {
        var order = confirmedOrder(10, 0);
        long orderId = order.path("id").asLong();
        long firstItemId = itemId(orderId, 1);
        // 模拟阶段五写入：捏毛装袋已流入 9 件
        jdbcTemplate.update(
                "UPDATE order_item_fulfillment_balances SET packing_inflow = 9 WHERE order_item_id = ?",
                firstItemId);

        var missing = createChange(orderId, """
                {"reason":"减单","items":[{"orderItemId":%d,"quantity":6}]}
                """.formatted(firstItemId));
        mockMvc.perform(post("/api/order-changes/" + missing.path("id").asLong() + "/confirm")
                        .cookie(sessionCookie).header("Idempotency-Key", key()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUANTITY_REQUIRES_DISPOSITION"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='items[0].surplusDisposition')]").isNotEmpty());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM order_items WHERE id = ?", Integer.class, firstItemId)).isEqualTo(10);

        // 逐项提交处理方案（改同一张草稿）后确认成功，方案结构化落库
        mockMvc.perform(patch("/api/order-changes/" + missing.path("id").asLong())
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"减单","items":[{"orderItemId":%d,"quantity":6,
                                 "surplusDisposition":"FINISH_TO_SURPLUS","surplusQuantity":3,
                                 "surplusReason":"继续完成转余量"}]}
                                """.formatted(firstItemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].surplusDisposition").value("FINISH_TO_SURPLUS"));
        mockMvc.perform(post("/api/order-changes/" + missing.path("id").asLong() + "/confirm")
                        .cookie(sessionCookie).header("Idempotency-Key", key()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.change.status").value("CONFIRMED"));
        var disposition = jdbcTemplate.queryForMap("""
                SELECT surplus_disposition, surplus_quantity, surplus_reason FROM order_change_items
                WHERE change_order_id = ?
                """, missing.path("id").asLong());
        assertThat(disposition.get("surplus_disposition")).isEqualTo("FINISH_TO_SURPLUS");
        assertThat(((Number) disposition.get("surplus_quantity")).intValue()).isEqualTo(3);
        assertThat(disposition.get("surplus_reason")).isEqualTo("继续完成转余量");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM order_items WHERE id = ?", Integer.class, firstItemId)).isEqualTo(6);
    }

    // ---------- 3.9 取消 ----------

    @Test
    void cancelsDraftAndFactFreeConfirmedOrderOnly() throws Exception {
        var draft = createDraft();
        mockMvc.perform(post("/api/orders/" + draft.path("id").asLong() + "/cancel")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"客户撤单\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        var factFree = confirmedOrder(5, 0);
        mockMvc.perform(post("/api/orders/" + factFree.path("id").asLong() + "/cancel")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"无事实取消\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        var withFacts = confirmedOrder(5, 0);
        long orderId = withFacts.path("id").asLong();
        // 模拟阶段四写入一条库存领用执行事实（需求事实不算执行事实）
        jdbcTemplate.update("""
                INSERT INTO fulfillment_entries (order_id, order_item_id, entry_type, node, direction, quantity,
                    source_type, source_id, source_line_id, business_date, created_at, updated_at)
                VALUES (?, ?, 'INVENTORY_ALLOCATION', 'PACKING_BAG', 'IN', 1, 'INVENTORY', 999, 0,
                    '2026-09-24', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, itemId(orderId, 1));
        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"直接取消\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CANCEL_NOT_ALLOWED"));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .as("拒绝取消不得改变状态").isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_entries WHERE order_id = ?", Integer.class, orderId))
                .as("拒绝取消不得删除历史事实").isEqualTo(2);

        // 终态不可重复取消
        mockMvc.perform(post("/api/orders/" + factFree.path("id").asLong() + "/cancel")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"重复取消\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CANCEL_NOT_ALLOWED"));
    }

    // ---------- 工具 ----------

    private JsonNode createDraft() throws Exception {
        var created = mockMvc.perform(post("/api/orders")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,"orderDate":"2026-09-24",
                                 "items":[{"productId":%d,"quantity":10,"seamQuantity":0}]}
                                """.formatted(customerId, productId)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).path("data");
    }

    private JsonNode confirmedOrder(int quantity, int seamQuantity) throws Exception {
        var draft = mockMvc.perform(post("/api/orders")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,"orderDate":"2026-09-24",
                                 "items":[{"productId":%d,"quantity":%d,"seamQuantity":%d}]}
                                """.formatted(customerId, productId, quantity, seamQuantity)))
                .andExpect(status().isCreated())
                .andReturn();
        var order = objectMapper.readTree(draft.getResponse().getContentAsString()).path("data");
        mockMvc.perform(post("/api/orders/" + order.path("id").asLong() + "/confirm")
                        .cookie(sessionCookie).header("Idempotency-Key", key()))
                .andExpect(status().isOk());
        return order;
    }

    private JsonNode createChange(long orderId, String body) throws Exception {
        var created = mockMvc.perform(post("/api/orders/" + orderId + "/change-orders")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).path("data");
    }

    private long itemId(long orderId, int lineNo) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND line_no = ?", Long.class, orderId, lineNo);
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}

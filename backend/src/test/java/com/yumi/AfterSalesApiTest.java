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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 8.11：部分发货即可创建、无效/重复/超量来源拒绝、退回等式、退回不入库、来源独立、
 * 补发确认时点、原订单统计不变、售后退款不改变结清口径和主状态。
 * 口径见 `domain-and-quantity-model.md` §12 与施工文档 §4。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AfterSalesApiTest {

    private static final String USERNAME = "after-sales-admin";
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
    private long confirmedShipmentItemId;
    private long draftShipmentItemId;
    private long shippableBatchId;
    private long makingBatchId;

    @BeforeEach
    void setup() throws Exception {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO admin_accounts (username, password_hash, status, version, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, USERNAME, passwordEncoder.encode(PASSWORD));
        jdbcTemplate.update("""
                INSERT INTO products (product_no, name, status, star_level_id, star_name, star_std_minutes,
                    sale_price, weight_g, total_cost, mold_quantity, daily_batch_limit, version, created_at, updated_at)
                VALUES ('TAS001', 'TST-售后商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 6.0000, 10, 5, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TAS001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, version, created_at, updated_at)
                VALUES ('TAS001', 'TST-售后客户', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TAS001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, customer_id, customer_name, status, order_date, recipient_name,
                    recipient_phone, region, address, goods_amount, seam_amount, discount_amount,
                    receivable_amount, goods_cost_amount, seam_cost_amount, cost_amount, profit_amount,
                    version, created_at, updated_at)
                VALUES ('TAS0001', ?, 'TST-售后客户', 'CONFIRMED', '2026-09-25', '收货人', '13800000000',
                    '华东', '地址', 100.0000, 0.0000, 0.0000, 100.0000, 60.0000, 0.0000, 60.0000, 40.0000,
                    1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, customerId);
        orderId = jdbcTemplate.queryForObject("SELECT id FROM orders WHERE order_no = 'TAS0001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO order_items (order_id, line_no, product_id, product_no, product_name,
                    quantity, seam_quantity, unit_price, goods_amount, unit_cost, goods_cost_amount,
                    version, created_at, updated_at)
                VALUES (?, 1, ?, 'TAS001', 'TST-售后商品', 10, 0, 10.0000, 100.0000, 6.0000, 60.0000,
                    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, productId);
        orderItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND line_no = 1", Long.class, orderId);
        // 部分发货：已确认批次 4 件 + 草稿批次 3 件
        jdbcTemplate.update("""
                INSERT INTO order_item_fulfillment_balances (order_id, order_item_id, required_quantity,
                    shippable_quantity, shipped_quantity, version, created_at, updated_at)
                VALUES (?, ?, 10, 6, 4, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, orderItemId);
        confirmedShipmentItemId = insertShipment("SH900001", "CONFIRMED", 4);
        draftShipmentItemId = insertShipment("SH900002", "DRAFT", 3);
        // 售后补发的库存来源：成品批次（可发货）；另备一个制作批次用于兼容性拒绝
        jdbcTemplate.update("""
                INSERT INTO inventory_batches (batch_no, product_id, product_no, product_name, source_type,
                    source_id, source_line_id, node, seam_state, quantity, inventory_date, created_at, updated_at)
                VALUES ('IB900001', ?, 'TAS001', 'TST-售后商品', 'OPENING', 1, 0, 'SHIPPABLE', 'NONE', 6,
                    '2026-09-25', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, productId);
        shippableBatchId = jdbcTemplate.queryForObject(
                "SELECT id FROM inventory_batches WHERE batch_no = 'IB900001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO inventory_batches (batch_no, product_id, product_no, product_name, source_type,
                    source_id, source_line_id, node, seam_state, quantity, inventory_date, created_at, updated_at)
                VALUES ('IB900002', ?, 'TAS001', 'TST-售后商品', 'OPENING', 2, 0, 'MAKING', 'NONE', 3,
                    '2026-09-25', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, productId);
        makingBatchId = jdbcTemplate.queryForObject(
                "SELECT id FROM inventory_batches WHERE batch_no = 'IB900002'", Long.class);
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
    }

    @AfterEach
    void cleanup() {
        var testOrders = "SELECT id FROM orders WHERE order_no = 'TAS0001'";
        var shipments = "SELECT id FROM shipments WHERE order_id IN (" + testOrders + ")";
        var cases = "SELECT id FROM after_sales_cases WHERE order_id IN (" + testOrders + ")";
        var items = "SELECT id FROM after_sales_items WHERE case_id IN (" + cases + ")";
        jdbcTemplate.update("DELETE FROM after_sales_corrections WHERE case_id IN (" + cases + ")");
        jdbcTemplate.update("DELETE FROM after_sales_fulfillment_entries WHERE after_sales_item_id IN ("
                + items + ")");
        jdbcTemplate.update("DELETE FROM after_sales_shipment_links WHERE after_sales_item_id IN (" + items + ")");
        jdbcTemplate.update("DELETE FROM after_sales_return_verifications WHERE after_sales_item_id IN ("
                + items + ")");
        jdbcTemplate.update("DELETE FROM after_sales_items WHERE case_id IN (" + cases + ")");
        jdbcTemplate.update("DELETE FROM after_sales_cases WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM inventory_movement_lines WHERE batch_id IN "
                + "(SELECT id FROM inventory_batches WHERE batch_no LIKE 'IB9%')");
        // 冲销流水引用原流水：先清无明细的冲销流水，再清其余无明细流水（与既有库存测试同口径）
        jdbcTemplate.update("DELETE FROM inventory_movements WHERE reverses_movement_id IS NOT NULL "
                + "AND id NOT IN (SELECT DISTINCT movement_id FROM inventory_movement_lines)");
        jdbcTemplate.update("DELETE FROM inventory_movements WHERE id NOT IN "
                + "(SELECT DISTINCT movement_id FROM inventory_movement_lines)");
        jdbcTemplate.update("DELETE FROM inventory_batches WHERE batch_no LIKE 'IB9%'");
        jdbcTemplate.update("DELETE FROM refunds WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM payments WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_settlement_balances WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM shipment_source_links WHERE shipment_item_id IN "
                + "(SELECT id FROM shipment_items WHERE shipment_id IN (" + shipments + "))");
        jdbcTemplate.update("DELETE FROM shipment_logistics_changes WHERE shipment_id IN (" + shipments + ")");
        jdbcTemplate.update("DELETE FROM shipment_corrections WHERE original_shipment_id IN (" + shipments
                + ") OR replacement_shipment_id IN (" + shipments + ")");
        jdbcTemplate.update("UPDATE shipments SET replaces_shipment_id = NULL WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM shipment_items WHERE shipment_id IN (" + shipments + ")");
        jdbcTemplate.update("DELETE FROM shipments WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM fulfillment_entries WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_item_fulfillment_balances WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_items WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM orders WHERE order_no = 'TAS0001'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TAS001'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TAS001'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    @Test
    void createsCaseOnlyFromConfirmedShipmentWithinAcceptedQuantity() throws Exception {
        // 草稿批次不能作为售后来源
        createCase(draftShipmentItemId, 2, 2, 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_SOURCE_INVALID"));

        // 受理数量不得超过该发货明细的有效已发数量（4）
        createCase(confirmedShipmentItemId, 5, 5, 5)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_QUANTITY_EXCEEDED"));

        var created = createCase(confirmedShipmentItemId, 4, 3, 4)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.caseNo").value(org.hamcrest.Matchers.startsWith("AS")))
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].acceptedQuantity").value(4))
                .andExpect(jsonPath("$.data.items[0].availableQuantity").value(0))
                .andExpect(jsonPath("$.data.items[0].shippedQuantity").value(0))
                .andExpect(jsonPath("$.data.items[0].pendingQuantity").value(4))
                .andReturn();
        long caseId = dataId(created);

        // 同一发货批次明细只允许一个有效售后占用
        createCase(confirmedShipmentItemId, 1, 1, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_QUANTITY_EXCEEDED"));
        // 订单部分发货但未关闭也可创建（本用例已证明），列表可见
        mockMvc.perform(get("/api/orders/" + orderId + "/after-sales").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        assertThat(caseId).isPositive();
    }

    @Test
    void rejectsReplacementShipmentAsAfterSalesSource() throws Exception {
        // 造一个「售后补发批次」：新发货批次 + 明细 + 售后补发关联，且状态为已确认
        jdbcTemplate.update("""
                INSERT INTO shipments (shipment_no, order_id, status, shipment_date, freight, current_freight,
                    created_at, updated_at)
                VALUES ('SH900009', ?, 'CONFIRMED', '2026-09-25', 0.0000, 0.0000, UTC_TIMESTAMP(6),
                    UTC_TIMESTAMP(6))
                """, orderId);
        long replacementShipmentId = jdbcTemplate.queryForObject(
                "SELECT id FROM shipments WHERE shipment_no = 'SH900009'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO shipment_items (shipment_id, order_item_id, line_no, quantity, product_no,
                    product_name, created_at, updated_at)
                VALUES (?, ?, 1, 2, 'TAS001', 'TST-售后商品', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, replacementShipmentId, orderItemId);
        long replacementItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM shipment_items WHERE shipment_id = ?", Long.class, replacementShipmentId);
        jdbcTemplate.update("""
                INSERT INTO after_sales_shipment_links (after_sales_item_id, shipment_id, shipment_item_id,
                    quantity, version, created_at, updated_at)
                VALUES (?, ?, ?, 2, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, itemIdForLink(), replacementShipmentId, replacementItemId);

        // 用售后补发批次明细创建售后 → 409（补发品不是原发货批次明细）
        mockMvc.perform(post("/api/orders/" + orderId + "/after-sales")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"caseType":"REWORK","problem":"验收-补发批次不应作为来源",
                                 "items":[{"shipmentItemId":%d,"acceptedQuantity":2}]}
                                """.formatted(replacementItemId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_SOURCE_INVALID"));

        // 清理本用例自造的发货批次与关联
        jdbcTemplate.update("DELETE FROM after_sales_shipment_links WHERE shipment_id = ?", replacementShipmentId);
        jdbcTemplate.update("DELETE FROM shipment_items WHERE shipment_id = ?", replacementShipmentId);
        jdbcTemplate.update("DELETE FROM shipments WHERE id = ?", replacementShipmentId);
    }

    /** 关联行需要指向一个真实售后明细：先建一条售后单与明细（本用例内自足）。 */
    private long itemIdForLink() throws Exception {
        var created = mockMvc.perform(post("/api/orders/" + orderId + "/after-sales")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"caseType":"REPLACEMENT","problem":"验收-补发来源用例",
                                 "items":[{"shipmentItemId":%d,"acceptedQuantity":2,"replacementRequiredQuantity":2}]}
                                """.formatted(confirmedShipmentItemId)))
                .andExpect(status().isCreated())
                .andReturn();
        return jdbcTemplate.queryForObject(
                "SELECT id FROM after_sales_items WHERE case_id = ?", Long.class, dataId(created));
    }

    @Test
    void verifiesReturnEquationOnceAndDoesNotTouchInventory() throws Exception {
        long caseId = dataId(createCase(confirmedShipmentItemId, 4, 3, 4)
                .andExpect(status().isCreated()).andReturn());
        long itemId = firstItemId(caseId);
        int movementsBefore = count("inventory_movements");

        // 退回 ≠ 返工 + 报废
        verifyReturn(caseId, itemId, 3, 2, 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_EQUATION_INVALID"));
        // 退回不得超过受理数量
        verifyReturn(caseId, itemId, 5, 4, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_QUANTITY_EXCEEDED"));

        verifyReturn(caseId, itemId, 3, 2, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].returnVerified").value(true))
                .andExpect(jsonPath("$.data.items[0].reworkQuantity").value(2))
                .andExpect(jsonPath("$.data.items[0].scrapQuantity").value(1));
        // 退回核验只能一次
        verifyReturn(caseId, itemId, 3, 3, 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_ALREADY_VERIFIED"));
        // 退回不自动入库、不恢复原发货库存
        assertThat(count("inventory_movements")).isEqualTo(movementsBefore);
        assertThat(shippedOfOrderItem()).as("原订单累计发货不变").isEqualTo(4);
    }

    @Test
    void replacementShipmentRequiresAvailableQuantityAndKeepsOrderUntouched() throws Exception {
        long caseId = dataId(createCase(confirmedShipmentItemId, 4, 3, 4)
                .andExpect(status().isCreated()).andReturn());
        long itemId = firstItemId(caseId);

        // 尚无补发来源 → 确认被拒（可补发 0）
        var draft = createReplacementShipment(caseId, itemId, 4)
                .andExpect(status().isCreated())
                .andReturn();
        long shipmentId = objectMapper.readTree(draft.getResponse().getContentAsString())
                .path("data").path("items").path(0).path("id").asLong();
        assertThat(shipmentId).isPositive();
        long replacementShipmentId = latestReplacementShipmentId(caseId);
        confirmReplacement(caseId, replacementShipmentId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_REPLACEMENT_INSUFFICIENT"));

        // 库存接入 4 件到售后可补发（阶段八的库存领用路径；此处直接写台账事实）
        jdbcTemplate.update("""
                INSERT INTO after_sales_fulfillment_entries (after_sales_item_id, entry_type, direction,
                    quantity, source_type, source_id, source_line_id, business_date, created_at, updated_at)
                VALUES (?, 'INVENTORY_INFLOW', 'IN', 4, 'INVENTORY_ALLOCATION', 1, 0, '2026-09-25',
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, itemId);
        confirmReplacement(caseId, replacementShipmentId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].shippedQuantity").value(4))
                .andExpect(jsonPath("$.data.items[0].availableQuantity").value(0))
                .andExpect(jsonPath("$.data.items[0].pendingQuantity").value(0));

        // 原订单统计不变：订购/累计发货/未交付/应收/主状态
        assertThat(shippedOfOrderItem()).isEqualTo(4);
        var order = jdbcTemplate.queryForMap("""
                SELECT o.status, o.receivable_amount, b.required_quantity, b.shipped_quantity
                FROM orders o JOIN order_item_fulfillment_balances b ON b.order_id = o.id
                WHERE o.id = ? AND b.order_item_id = ?
                """, orderId, orderItemId);
        assertThat(order.get("status")).isEqualTo("CONFIRMED");
        // MySQL INT UNSIGNED 经 queryForMap 返回 Long，统一按 Number 取值比较
        assertThat(((Number) order.get("required_quantity")).intValue()).isEqualTo(10);
        assertThat(((Number) order.get("shipped_quantity")).intValue()).isEqualTo(4);
    }

    @Test
    void correctionsKeepOriginalVerification() throws Exception {
        long caseId = dataId(createCase(confirmedShipmentItemId, 4, 3, 4)
                .andExpect(status().isCreated()).andReturn());
        long itemId = firstItemId(caseId);
        verifyReturn(caseId, itemId, 3, 2, 1).andExpect(status().isOk());

        mockMvc.perform(post("/api/after-sales/" + caseId + "/corrections")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"RETURN_VERIFICATION\",\"targetId\":" + itemId
                                + ",\"afterValue\":\"2\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='reason')]").isNotEmpty());

        mockMvc.perform(post("/api/after-sales/" + caseId + "/corrections")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"RETURN_VERIFICATION\",\"targetId\":" + itemId
                                + ",\"beforeValue\":\"3\",\"afterValue\":\"2\",\"reason\":\"退回数量录错\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.corrections.length()").value(1))
                // 读模型必须回传原值/新值/原因（任务 8.9「保留原值与来源链」；此前这三列被 SQL 映射丢弃、恒为 null）
                .andExpect(jsonPath("$.data.corrections[0].beforeValue").value("3"))
                .andExpect(jsonPath("$.data.corrections[0].afterValue").value("2"))
                .andExpect(jsonPath("$.data.corrections[0].reason").value("退回数量录错"));
        // 原核验不变
        assertThat(jdbcTemplate.queryForObject("""
                SELECT returned_quantity FROM after_sales_return_verifications WHERE after_sales_item_id = ?
                """, Integer.class, itemId)).isEqualTo(3);
    }

    @Test
    void afterSalesRefundRequiresCaseAndKeepsSettlementAndStatus() throws Exception {
        long caseId = dataId(createCase(confirmedShipmentItemId, 4, 3, 4)
                .andExpect(status().isCreated()).andReturn());
        jdbcTemplate.update("""
                INSERT INTO payments (payment_no, order_id, amount, business_date, method, created_at, updated_at)
                VALUES ('PA900001', ?, 100.0000, '2026-09-25', '微信', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId);

        // 售后单不存在 → 来源无效
        refund("AFTER_SALES", 999999, "30.0000")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_REFERENCE_REQUIRED"));

        refund("AFTER_SALES", caseId, "30.0000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.afterSalesRefundAmount").value("30.0000"))
                .andExpect(jsonPath("$.data.netSettledAmount").value("100.0000"))
                .andExpect(jsonPath("$.data.actualNetReceived").value("70.0000"))
                .andExpect(jsonPath("$.data.refundPendingAmount").value("0.0000"));
        // 主状态不变
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId)).isEqualTo("CONFIRMED");
    }

    @Test
    void allocatesInventoryToAfterSalesAndReversesWithMovement() throws Exception {
        long caseId = dataId(createCase(confirmedShipmentItemId, 4, 3, 4)
                .andExpect(status().isCreated()).andReturn());
        long itemId = firstItemId(caseId);

        // 只能领用成品批次（可发货）
        allocateToAfterSales(itemId, makingBatchId, 2)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"));
        // 库存不足
        allocateToAfterSales(itemId, shippableBatchId, 9)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_INSUFFICIENT"));

        var allocated = allocateToAfterSales(itemId, shippableBatchId, 4)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.movementType").value("AFTER_SALES_ALLOCATION"))
                .andExpect(jsonPath("$.data.sourceType").value("AFTER_SALES"))
                .andReturn();
        assertThat(batchQuantity(shippableBatchId)).as("库存只扣一次").isEqualTo(2);
        assertThat(availableOf(caseId)).as("只增加售后可补发").isEqualTo(4);
        assertThat(shippedOfOrderItem()).as("原订单累计发货不变").isEqualTo(4);

        // 冲销原流水：恢复批次并同步冲销售后台账事实
        long movementId = objectMapper.readTree(allocated.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        mockMvc.perform(post("/api/inventory/movements/" + movementId + "/reverse")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"录错批次\"}"))
                .andExpect(status().isOk());
        assertThat(batchQuantity(shippableBatchId)).isEqualTo(6);
        assertThat(availableOf(caseId)).as("售后可补发同步回退").isZero();

        // 重新领用后可补发并确认补发发货
        allocateToAfterSales(itemId, shippableBatchId, 4).andExpect(status().isCreated());
        createReplacementShipment(caseId, itemId, 4).andExpect(status().isCreated());
        confirmReplacement(caseId, latestReplacementShipmentId(caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].shippedQuantity").value(4))
                .andExpect(jsonPath("$.data.items[0].availableQuantity").value(0))
                .andExpect(jsonPath("$.data.items[0].pendingQuantity").value(0));
        assertThat(batchQuantity(shippableBatchId)).as("补发发货不再扣原库存").isEqualTo(2);
    }

    // ---------- 工具 ----------

    private org.springframework.test.web.servlet.ResultActions allocateToAfterSales(long itemId, long batchId,
                                                                                   int quantity)
            throws Exception {
        return mockMvc.perform(post("/api/inventory/after-sales-allocations")
                .cookie(sessionCookie).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"afterSalesItemId":%d,"batchId":%d,"quantity":%d,"reason":"售后补发领用"}
                        """.formatted(itemId, batchId, quantity)));
    }

    private int batchQuantity(long batchId) {
        var value = jdbcTemplate.queryForObject(
                "SELECT quantity FROM inventory_batches WHERE id = ?", Integer.class, batchId);
        return value == null ? 0 : value;
    }

    private int availableOf(long caseId) {
        var value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'IN' THEN quantity ELSE -quantity END), 0)
                FROM after_sales_fulfillment_entries WHERE after_sales_item_id IN
                (SELECT id FROM after_sales_items WHERE case_id = ?)
                """, Integer.class, caseId);
        return value == null ? 0 : value;
    }


    private org.springframework.test.web.servlet.ResultActions createCase(long shipmentItemId, int accepted,
                                                                         int returned, int replacementRequired)
            throws Exception {
        return mockMvc.perform(post("/api/orders/" + orderId + "/after-sales")
                .cookie(sessionCookie).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"caseType":"REWORK_AND_REPLACEMENT","problem":"客户反馈破损",
                         "items":[{"shipmentItemId":%d,"acceptedQuantity":%d,"returnedQuantity":%d,
                         "replacementRequiredQuantity":%d}]}
                        """.formatted(shipmentItemId, accepted, returned, replacementRequired)));
    }

    private org.springframework.test.web.servlet.ResultActions verifyReturn(long caseId, long itemId,
                                                                           int returned, int rework, int scrap)
            throws Exception {
        return mockMvc.perform(post("/api/after-sales/" + caseId + "/verify-return")
                .cookie(sessionCookie).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"afterSalesItemId":%d,"returnedQuantity":%d,"reworkQuantity":%d,"scrapQuantity":%d,
                         "reason":"客户退回"}
                        """.formatted(itemId, returned, rework, scrap)));
    }

    private org.springframework.test.web.servlet.ResultActions createReplacementShipment(long caseId, long itemId,
                                                                                       int quantity)
            throws Exception {
        return mockMvc.perform(post("/api/after-sales/" + caseId + "/replacement-shipments")
                .cookie(sessionCookie).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"items":[{"afterSalesItemId":%d,"quantity":%d}]}
                        """.formatted(itemId, quantity)));
    }

    private org.springframework.test.web.servlet.ResultActions confirmReplacement(long caseId, long shipmentId)
            throws Exception {
        return mockMvc.perform(post("/api/after-sales/" + caseId + "/replacement-shipments/" + shipmentId
                        + "/confirm")
                .cookie(sessionCookie).header("Idempotency-Key", key()));
    }

    private org.springframework.test.web.servlet.ResultActions refund(String sourceType, long sourceId,
                                                                     String amount) throws Exception {
        return mockMvc.perform(post("/api/orders/" + orderId + "/refunds")
                .cookie(sessionCookie).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount":"%s","businessDate":"2026-09-25","method":"微信","reason":"售后退款",
                         "sourceType":"%s","sourceId":%d}
                        """.formatted(amount, sourceType, sourceId)));
    }

    private long insertShipment(String shipmentNo, String status, int quantity) {
        jdbcTemplate.update("""
                INSERT INTO shipments (shipment_no, order_id, status, shipment_date, freight, current_freight,
                    created_at, updated_at)
                VALUES (?, ?, ?, '2026-09-25', 0.0000, 0.0000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, shipmentNo, orderId, status);
        long shipmentId = jdbcTemplate.queryForObject(
                "SELECT id FROM shipments WHERE shipment_no = ?", Long.class, shipmentNo);
        jdbcTemplate.update("""
                INSERT INTO shipment_items (shipment_id, order_item_id, line_no, quantity, product_no,
                    product_name, created_at, updated_at)
                VALUES (?, ?, 1, ?, 'TAS001', 'TST-售后商品', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, shipmentId, orderItemId, quantity);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM shipment_items WHERE shipment_id = ?", Long.class, shipmentId);
    }

    private long firstItemId(long caseId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM after_sales_items WHERE case_id = ? ORDER BY id LIMIT 1", Long.class, caseId);
    }

    private long latestReplacementShipmentId(long caseId) {
        return jdbcTemplate.queryForObject("""
                SELECT shipment_id FROM after_sales_shipment_links WHERE after_sales_item_id IN
                (SELECT id FROM after_sales_items WHERE case_id = ?) ORDER BY id DESC LIMIT 1
                """, Long.class, caseId);
    }

    private long dataId(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.path("data").path("id").asLong();
    }

    private int shippedOfOrderItem() {
        var value = jdbcTemplate.queryForObject(
                "SELECT shipped_quantity FROM order_item_fulfillment_balances WHERE order_item_id = ?",
                Integer.class, orderItemId);
        return value == null ? 0 : value;
    }

    private int count(String table) {
        var value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private String key() {
        return java.util.UUID.randomUUID().toString();
    }
}

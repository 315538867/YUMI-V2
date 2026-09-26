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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 9.1/9.2/9.4：六类台账查询（服务端筛选与分页）、导出固定字段清单且**不含物流字段**、
 * 事实重建与投影一致性检查（不一致时产出失败证据）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportApiTest {

    private static final String USERNAME = "report-admin";
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
    private long batchId;

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
                VALUES ('TRP001', 'TST-报表商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 6.0000, 10, 5, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TRP001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, version, created_at, updated_at)
                VALUES ('TRP001', 'TST-报表客户', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TRP001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, customer_id, customer_name, status, order_date, recipient_name,
                    recipient_phone, region, address, goods_amount, seam_amount, discount_amount,
                    receivable_amount, goods_cost_amount, seam_cost_amount, cost_amount, profit_amount,
                    version, created_at, updated_at)
                VALUES ('TRP0001', ?, 'TST-报表客户', 'CONFIRMED', '2026-09-25', '收货人', '13800000000',
                    '华东', '地址', 100.0000, 0.0000, 0.0000, 100.0000, 60.0000, 0.0000, 60.0000, 40.0000,
                    1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, customerId);
        orderId = jdbcTemplate.queryForObject("SELECT id FROM orders WHERE order_no = 'TRP0001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO order_items (order_id, line_no, product_id, product_no, product_name,
                    quantity, seam_quantity, unit_price, goods_amount, unit_cost, goods_cost_amount,
                    version, created_at, updated_at)
                VALUES (?, 1, ?, 'TRP001', 'TST-报表商品', 10, 0, 10.0000, 100.0000, 6.0000, 60.0000,
                    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, productId);
        orderItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND line_no = 1", Long.class, orderId);
        jdbcTemplate.update("""
                INSERT INTO order_item_fulfillment_balances (order_id, order_item_id, required_quantity,
                    shippable_quantity, shipped_quantity, version, created_at, updated_at)
                VALUES (?, ?, 10, 6, 4, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, orderItemId);
        // 发货事实与投影保持自洽（供一致性检查通过）：
        // 可发货 = 流入可发货（库存接入 10，不含 ORDER_DEMAND 需求登记）− 发货消耗 4 = 6，与投影一致
        jdbcTemplate.update("""
                INSERT INTO fulfillment_entries (order_id, order_item_id, entry_type, node, direction, quantity,
                    source_type, source_id, source_line_id, business_date, created_at, updated_at)
                VALUES (?, ?, 'INVENTORY_ALLOCATION', 'SHIPPABLE', 'IN', 10, 'INVENTORY', 1, 1, '2026-09-25',
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, orderItemId);
        jdbcTemplate.update("""
                INSERT INTO fulfillment_entries (order_id, order_item_id, entry_type, node, direction, quantity,
                    source_type, source_id, source_line_id, business_date, created_at, updated_at)
                VALUES (?, ?, 'SHIPMENT_CONSUME', 'SHIPPABLE', 'OUT', 4, 'SHIPMENT', 1, 1, '2026-09-25',
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, orderItemId);
        // 已确认发货批次（带物流字段，用于验证导出不含物流）
        jdbcTemplate.update("""
                INSERT INTO shipments (shipment_no, order_id, status, shipment_date, carrier, tracking_no,
                    freight, logistics_note, current_carrier, current_tracking_no, current_freight,
                    current_logistics_note, created_at, updated_at)
                VALUES ('SH970001', ?, 'CONFIRMED', '2026-09-25', '中通', 'ZT123456', 8.0000, '物流备注',
                    '中通', 'ZT123456', 8.0000, '物流备注', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId);
        long shipmentId = jdbcTemplate.queryForObject(
                "SELECT id FROM shipments WHERE shipment_no = 'SH970001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO shipment_items (shipment_id, order_item_id, line_no, quantity, product_no,
                    product_name, cumulative_shipped_quantity, undelivered_quantity, created_at, updated_at)
                VALUES (?, ?, 1, 4, 'TRP001', 'TST-报表商品', 4, 6, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, shipmentId, orderItemId);
        // 库存批次与流水自洽
        jdbcTemplate.update("""
                INSERT INTO inventory_movements (movement_no, movement_type, business_date, source_type,
                    source_id, source_line_id, created_at, updated_at)
                VALUES ('IM970001', 'OPENING', '2026-09-25', 'OPENING', 1, 0, UTC_TIMESTAMP(6),
                    UTC_TIMESTAMP(6))
                """);
        long movementId = jdbcTemplate.queryForObject(
                "SELECT id FROM inventory_movements WHERE movement_no = 'IM970001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO inventory_batches (batch_no, product_id, product_no, product_name, source_type,
                    source_id, source_line_id, node, seam_state, quantity, inventory_date, created_at, updated_at)
                VALUES ('IB970001', ?, 'TRP001', 'TST-报表商品', 'OPENING', 1, 0, 'SHIPPABLE', 'NONE', 6,
                    '2026-09-25', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, productId);
        batchId = jdbcTemplate.queryForObject(
                "SELECT id FROM inventory_batches WHERE batch_no = 'IB970001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO inventory_movement_lines (movement_id, batch_id, direction, quantity, quantity_before,
                    quantity_after, product_id, node, seam_state, created_at, updated_at)
                VALUES (?, ?, 'IN', 6, 0, 6, ?, 'SHIPPABLE', 'NONE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, movementId, batchId, productId);
        // 收款
        jdbcTemplate.update("""
                INSERT INTO payments (payment_no, order_id, amount, business_date, method, created_at, updated_at)
                VALUES ('PA970001', ?, 100.0000, '2026-09-25', '微信', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId);
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
    }

    @AfterEach
    void cleanup() {
        var testOrders = "SELECT id FROM orders WHERE order_no = 'TRP0001'";
        var testBatches = "SELECT id FROM inventory_batches WHERE batch_no = 'IB970001'";
        // 售后明细引用 shipment_items，必须在删除发货明细之前清理
        var testCases = "SELECT id FROM after_sales_cases WHERE order_id IN (" + testOrders + ")";
        var testItems = "SELECT id FROM after_sales_items WHERE case_id IN (" + testCases + ")";
        jdbcTemplate.update("DELETE FROM after_sales_shipment_links WHERE after_sales_item_id IN ("
                + testItems + ")");
        jdbcTemplate.update("DELETE FROM after_sales_fulfillment_entries WHERE after_sales_item_id IN ("
                + testItems + ")");
        jdbcTemplate.update("DELETE FROM after_sales_items WHERE case_id IN (" + testCases + ")");
        jdbcTemplate.update("DELETE FROM after_sales_cases WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM inventory_movement_lines WHERE batch_id IN (" + testBatches + ")");
        jdbcTemplate.update("DELETE FROM inventory_batches WHERE batch_no = 'IB970001'");
        jdbcTemplate.update("DELETE FROM inventory_movements WHERE movement_no = 'IM970001'");
        jdbcTemplate.update("DELETE FROM shipment_items WHERE shipment_id IN "
                + "(SELECT id FROM shipments WHERE order_id IN (" + testOrders + "))");
        jdbcTemplate.update("DELETE FROM shipments WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM payments WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM fulfillment_entries WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_item_fulfillment_balances WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_items WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM orders WHERE order_no = 'TRP0001'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TRP001'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TRP001'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    @Test
    void queriesAllSixLedgersWithServerSidePagingAndFilter() throws Exception {
        for (var type : new String[]{"ORDER_FULFILLMENT", "INVENTORY", "PRODUCTION", "SHIPMENT",
                "SETTLEMENT", "AFTER_SALES"}) {
            mockMvc.perform(get("/api/reports/" + type).cookie(sessionCookie))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.type").value(type))
                    .andExpect(jsonPath("$.data.columns.length()").value(org.hamcrest.Matchers.greaterThan(0)));
        }
        // 分页由服务端执行
        mockMvc.perform(get("/api/reports/ORDER_FULFILLMENT").cookie(sessionCookie)
                        .param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.size").value(1));
        // 按订单筛选
        mockMvc.perform(get("/api/reports/ORDER_FULFILLMENT").cookie(sessionCookie)
                        .param("orderId", String.valueOf(orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].orderNo").value("TRP0001"))
                // 金额以字符串输出
                .andExpect(jsonPath("$.data.rows[0].receivableAmount").value("100.0000"));
        // 日期区间筛选（当天命中，前一天不命中）
        mockMvc.perform(get("/api/reports/SHIPMENT").cookie(sessionCookie).param("dateFrom", "2026-09-26"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        // 未知类型
        mockMvc.perform(get("/api/reports/UNKNOWN").cookie(sessionCookie))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_TYPE_INVALID"));
    }

    @Test
    void exportUsesFixedFieldListWithoutLogisticsFields() throws Exception {
        var csv = mockMvc.perform(get("/api/reports/SHIPMENT/export").cookie(sessionCookie)
                        .param("orderId", String.valueOf(orderId)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // 固定业务字段清单
        assertThat(csv).contains("批次编号").contains("订单编号").contains("本次数量").contains("累计发货快照");
        // **不含物流公司、单号、运费与发货备注**
        for (var forbidden : new String[]{"物流公司", "单号", "运费", "物流备注"}) {
            assertThat(csv).as("导出不得包含物流字段 " + forbidden).doesNotContain(forbidden);
        }
        assertThat(csv).as("导出不得出现物流值").doesNotContain("中通").doesNotContain("ZT123456");
        // 金额与快照保持字符串
        assertThat(csv).contains("TRP0001").contains("4").contains("6");
    }

    @Test
    void consistencyCheckReportsMismatchWithoutSilentOverwrite() throws Exception {
        mockMvc.perform(get("/api/reports/consistency").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allConsistent").value(true))
                .andExpect(jsonPath("$.data.checks.length()").value(3));

        // 人为破坏库存投影（批次数量与有效流水不符）→ 检查必须报不一致且不修改数据
        jdbcTemplate.update("UPDATE inventory_batches SET quantity = 99 WHERE id = ?", batchId);
        mockMvc.perform(get("/api/reports/consistency").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allConsistent").value(false))
                .andExpect(jsonPath("$.data.checks[?(@.name=='库存批次数量与有效流水一致')].consistent")
                        .value(org.hamcrest.Matchers.contains(false)));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM inventory_batches WHERE id = ?", Integer.class, batchId))
                .as("一致性检查不做静默覆盖").isEqualTo(99);
    }

    /**
     * 任务 9.4 第三项检查（售后已补发 vs 补发发货关联）必须有**非空数据**覆盖：
     * 空集时该检查会真空通过，无法证明它真的在比对。
     */
    @Test
    void afterSalesCheckComparesConsumedAgainstLinks() throws Exception {
        long shipmentItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM shipment_items WHERE shipment_id = "
                        + "(SELECT id FROM shipments WHERE order_id = ?)", Long.class, orderId);
        jdbcTemplate.update("""
                INSERT INTO after_sales_cases (case_no, order_id, case_type, status, problem, version,
                    created_at, updated_at)
                VALUES ('AS870001', ?, 'REPLACEMENT', 'OPEN', '验收-一致性第三项', 0, UTC_TIMESTAMP(6),
                    UTC_TIMESTAMP(6))
                """, orderId);
        long caseId = jdbcTemplate.queryForObject(
                "SELECT id FROM after_sales_cases WHERE case_no = 'AS870001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO after_sales_items (case_id, order_id, order_item_id, shipment_item_id, product_no,
                    product_name, accepted_quantity, replacement_required_quantity, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'TRP001', 'TST-报表商品', 2, 2, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, caseId, orderId, orderItemId, shipmentItemId);
        long itemId = jdbcTemplate.queryForObject(
                "SELECT id FROM after_sales_items WHERE case_id = ?", Long.class, caseId);
        jdbcTemplate.update("""
                INSERT INTO after_sales_fulfillment_entries (after_sales_item_id, entry_type, direction,
                    quantity, source_type, source_id, source_line_id, business_date, created_at, updated_at)
                VALUES (?, 'REPLACEMENT_CONSUME', 'OUT', 2, 'AFTER_SALES_REPLACEMENT', 1, 1, '2026-09-25',
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, itemId);
        jdbcTemplate.update("""
                INSERT INTO after_sales_shipment_links (after_sales_item_id, shipment_id, shipment_item_id,
                    quantity, version, created_at, updated_at)
                VALUES (?, (SELECT id FROM shipments WHERE order_id = ?), ?, 2, 0, UTC_TIMESTAMP(6),
                    UTC_TIMESTAMP(6))
                """, itemId, orderId, shipmentItemId);

        // 消耗 2 = 关联 2 → 一致
        mockMvc.perform(get("/api/reports/consistency").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allConsistent").value(true));

        // 关联数量被改动（2 → 1）→ 第三项必须报不一致，且能定位到该售后明细
        jdbcTemplate.update("UPDATE after_sales_shipment_links SET quantity = 1 WHERE after_sales_item_id = ?",
                itemId);
        mockMvc.perform(get("/api/reports/consistency").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allConsistent").value(false))
                .andExpect(jsonPath("$.data.checks[?(@.name=='售后已补发与补发发货关联一致')].consistent")
                        .value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$.data.checks[?(@.name=='售后已补发与补发发货关联一致')].detail")
                        .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.containsString("AS".isEmpty()
                                ? "" : String.valueOf(itemId)))));
    }

    /**
     * 回归（由 9.7 恢复演练发现）：多工序流入时，检查必须按**正确节点列**比对，
     * 且必须覆盖可发货数量；初版把三列各错位一个节点且漏查可发货，此用例可复现该缺陷。
     */
    @Test
    void consistencyCheckMapsNodeColumnsAndShippable() throws Exception {
        // 造多工序事实：捏毛装袋流入 6（库存接入 4 + 上游合格 2）、缝边剪袋流入 2，投影同步
        jdbcTemplate.update("""
                INSERT INTO fulfillment_entries (order_id, order_item_id, entry_type, node, direction, quantity,
                    source_type, source_id, source_line_id, business_date, created_at, updated_at)
                VALUES (?, ?, 'INVENTORY_ALLOCATION', 'PACKING_BAG', 'IN', 4, 'INVENTORY', 1, 1, '2026-09-25',
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, orderItemId);
        jdbcTemplate.update("""
                INSERT INTO fulfillment_entries (order_id, order_item_id, entry_type, node, direction, quantity,
                    source_type, source_id, source_line_id, business_date, created_at, updated_at)
                VALUES (?, ?, 'PRODUCTION_QUALIFIED', 'PACKING_BAG', 'IN', 2, 'PRODUCTION', 1, 0, '2026-09-25',
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, orderItemId);
        jdbcTemplate.update("""
                INSERT INTO fulfillment_entries (order_id, order_item_id, entry_type, node, direction, quantity,
                    source_type, source_id, source_line_id, business_date, created_at, updated_at)
                VALUES (?, ?, 'PRODUCTION_QUALIFIED', 'SEAM_CUTTING', 'IN', 2, 'PRODUCTION', 1, 0, '2026-09-25',
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, orderItemId);
        jdbcTemplate.update("""
                UPDATE order_item_fulfillment_balances SET packing_inflow = 6, seam_inflow = 2
                WHERE order_item_id = ?
                """, orderItemId);

        mockMvc.perform(get("/api/reports/consistency").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allConsistent").value(true));

        // 需求基线事实（ORDER_CHANGE）不得计入物理重建：变更写出 SHIPPABLE 的 ORDER_CHANGE 出库后，
        // 投影按「物理流入 − 发货」保持不变 → 检查必须仍判一致（回归：初版把所有 OUT 都算作物理出库）
        jdbcTemplate.update("""
                INSERT INTO fulfillment_entries (order_id, order_item_id, entry_type, node, direction, quantity,
                    source_type, source_id, source_line_id, business_date, created_at, updated_at)
                VALUES (?, ?, 'ORDER_CHANGE', 'SHIPPABLE', 'OUT', 2, 'ORDER_CHANGE', 1, 0, '2026-09-25',
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, orderItemId);
        mockMvc.perform(get("/api/reports/consistency").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allConsistent").value(true));

        // 把捏毛装袋流入错记成缝边剪袋的数量 → 必须报不一致（列错位会被抓到）
        jdbcTemplate.update("""
                UPDATE order_item_fulfillment_balances SET packing_inflow = 2 WHERE order_item_id = ?
                """, orderItemId);
        mockMvc.perform(get("/api/reports/consistency").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allConsistent").value(false))
                .andExpect(jsonPath("$.data.checks[?(@.name=='履约余额与履约事实一致')].consistent")
                        .value(org.hamcrest.Matchers.contains(false)));

        // 只破坏可发货（初版漏查该列）→ 必须报不一致
        jdbcTemplate.update("""
                UPDATE order_item_fulfillment_balances SET packing_inflow = 6, shippable_quantity = 7
                WHERE order_item_id = ?
                """, orderItemId);
        mockMvc.perform(get("/api/reports/consistency").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allConsistent").value(false));
    }
}

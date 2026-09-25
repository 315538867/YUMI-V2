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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 5.6/5.7/5.8：返工来源目标矩阵与来源余额、重做来源起始工序与原因、从来源创建计划的余额约束，
 * 以及待执行计划取消后的来源恢复。口径见施工文档 §3.4/§3.5/§4.4/§4.5 与
 * `production-management` 的「返工必须受目标矩阵和来源余额限制」「报废重做必须保留原报废事实」
 * 「待执行计划取消必须恢复来源」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductionSourceTest {

    private static final String USERNAME = "production-source-admin";
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
    private long makerId;
    private long packerId;

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
                VALUES ('TSR001', 'TST-来源商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 6.0000, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TSR001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, version, created_at, updated_at)
                VALUES ('TSR001', 'TST-来源客户', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TSR001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, customer_id, customer_name, status, order_date, recipient_name,
                    recipient_phone, region, address, goods_amount, seam_amount, discount_amount,
                    receivable_amount, goods_cost_amount, seam_cost_amount, cost_amount, profit_amount,
                    version, created_at, updated_at)
                VALUES ('TSR0001', ?, 'TST-来源客户', 'CONFIRMED', '2026-09-25', '收货人', '13800000000',
                    '华东', '地址', 100.0000, 8.0000, 0.0000, 108.0000, 60.0000, 5.0000, 65.0000, 43.0000,
                    1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, customerId);
        orderId = jdbcTemplate.queryForObject("SELECT id FROM orders WHERE order_no = 'TSR0001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO order_items (order_id, line_no, product_id, product_no, product_name,
                    quantity, seam_quantity, unit_price, goods_amount, unit_cost, goods_cost_amount,
                    version, created_at, updated_at)
                VALUES (?, 1, ?, 'TSR001', 'TST-来源商品', 10, 4, 10.0000, 100.0000, 6.0000, 60.0000,
                    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, productId);
        orderItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND line_no = 1", Long.class, orderId);
        // 制作与捏毛装袋都已有 10 件有效流入，两个工序的计划都可执行
        jdbcTemplate.update("""
                INSERT INTO order_item_fulfillment_balances (order_id, order_item_id, required_quantity,
                    making_inflow, packing_inflow, version, created_at, updated_at)
                VALUES (?, ?, 10, 10, 10, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, orderItemId);
        makerId = insertEmployee("TSE001", "TST-制作员工", "MAKING");
        packerId = insertEmployee("TSE002", "TST-包装员工", "PACKING_BAG");
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
    }

    @AfterEach
    void cleanup() {
        var testOrder = "SELECT id FROM orders WHERE order_no = 'TSR0001'";
        var testPlans = "SELECT id FROM production_plans WHERE order_id IN (" + testOrder + ")";
        jdbcTemplate.update("DELETE FROM production_reminders WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM overtime_preemptions WHERE overtime_plan_id IN (" + testPlans + ")");
        jdbcTemplate.update("DELETE FROM remake_sources WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM rework_sources WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM production_verifications WHERE plan_id IN (" + testPlans + ")");
        jdbcTemplate.update("DELETE FROM production_plan_adjustments WHERE plan_id IN (" + testPlans + ")");
        jdbcTemplate.update("DELETE FROM production_plans WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM fulfillment_entries WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM order_item_fulfillment_balances WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM order_items WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM orders WHERE order_no = 'TSR0001'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TSR001'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TSR001'");
        jdbcTemplate.update("DELETE FROM employee_work_types WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no IN ('TSE001', 'TSE002'))");
        jdbcTemplate.update("DELETE FROM employees WHERE employee_no IN ('TSE001', 'TSE002')");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    @Test
    void enforcesReworkTargetMatrixAndSourceBalance() throws Exception {
        long verificationId = verifyPackingBagPlan(0, 6, 4);

        // 核验只记录返工额度，来源由本接口按目标工序显式创建
        assertThat(count("rework_sources")).isZero();
        assertThat(reworkPending()).isEqualTo(6);

        // 前序工序合法：捏毛装袋问题可以返工制作
        createReworkSource(verificationId, "MAKING", 3, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.targetNode").value("MAKING"))
                .andExpect(jsonPath("$.data.totalQuantity").value(3))
                .andExpect(jsonPath("$.data.balanceQuantity").value(3))
                .andExpect(jsonPath("$.data.roundNo").value(1));

        // 同一核验同一目标只能一条来源（额度仍够，先撞唯一键）
        createReworkSource(verificationId, "MAKING", 1, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT_DUPLICATE"));

        // 后序工序非法：捏毛装袋问题不能返工缝边剪袋
        createReworkSource(verificationId, "SEAM_CUTTING", 1, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REWORK_TARGET_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='targetNode')]").isNotEmpty());

        // 来源总量不得超过该核验的返工数量：3 + 4 > 6
        createReworkSource(verificationId, "MAKING", 4, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_INSUFFICIENT"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='quantity')]").isNotEmpty());

        // 来源创建不改变「返工待安排」额度（额度在核验时已计入，只有排产才消耗）
        assertThat(reworkPending()).isEqualTo(6);
    }

    @Test
    void enforcesRemakeStartNodeAndReason() throws Exception {
        long verificationId = verifyPackingBagPlan(0, 6, 4);

        assertThat(count("remake_sources")).isZero();
        assertThat(remakePending()).isEqualTo(4);

        // 从制作开始必须填写原因
        createRemakeSource(verificationId, "MAKING", 2, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REMAKE_REASON_REQUIRED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='reason')]").isNotEmpty());

        // 起始工序不能晚于报废工序（报废在捏毛装袋，不能从缝边剪袋开始）
        createRemakeSource(verificationId, "SEAM_CUTTING", 1, "晚于报废工序")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='startNode')]").isNotEmpty());

        // 填写原因后合法；同一核验同一起始工序只能一条
        createRemakeSource(verificationId, "MAKING", 2, "前序材料不可用")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.startNode").value("MAKING"))
                .andExpect(jsonPath("$.data.reason").value("前序材料不可用"));
        createRemakeSource(verificationId, "MAKING", 1, "再建一次")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT_DUPLICATE"));

        // 重做来源总量不得超过该核验的报废数量：2 + 3 > 4
        createRemakeSource(verificationId, "MAKING", 3, "超出报废数量")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_INSUFFICIENT"));

        // 默认从报废工序开始（捏毛装袋）也合法，与制作来源并存
        createRemakeSource(verificationId, "PACKING_BAG", 1, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.startNode").value("PACKING_BAG"));
        assertThat(remakePending()).isEqualTo(4);
    }

    @Test
    void createsPlanFromSourceAndReleasesBalanceOnCancel() throws Exception {
        long verificationId = verifyPackingBagPlan(0, 6, 4);
        var sourceCreated = createReworkSource(verificationId, "MAKING", 3, null)
                .andExpect(status().isCreated())
                .andReturn();
        long sourceId = objectMapper.readTree(sourceCreated.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // 从来源创建返工计划：数量 3 = 来源总量
        var created = mockMvc.perform(post("/api/rework-sources/" + sourceId + "/plans")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"planDate":"2026-09-25","employeeId":%d,"quantity":3,"note":"返工制作"}
                                """.formatted(makerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.planType").value("REWORK"))
                .andExpect(jsonPath("$.data.node").value("MAKING"))
                .andExpect(jsonPath("$.data.sourceType").value("REWORK_SOURCE"))
                .andReturn();
        long planId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        assertThat(sourceBalance(sourceId)).isZero();
        assertThat(reworkPending()).isEqualTo(3);
        assertThat(makingPlanned()).isEqualTo(3);

        // 余额不足：来源已排满
        mockMvc.perform(post("/api/rework-sources/" + sourceId + "/plans")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"planDate":"2026-09-26","employeeId":%d,"quantity":1}
                                """.formatted(makerId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_INSUFFICIENT"));

        // 取消待执行返工计划：来源余额恢复、计划占用释放、原计划保留
        mockMvc.perform(post("/api/production-plans/" + planId + "/cancel")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"排班调整\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        assertThat(sourceBalance(sourceId)).isEqualTo(3);
        assertThat(reworkPending()).isEqualTo(6);
        assertThat(makingPlanned()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT cancel_reason FROM production_plans WHERE id = ?", String.class, planId))
                .isEqualTo("排班调整");

        // 重复取消被拒绝
        mockMvc.perform(post("/api/production-plans/" + planId + "/cancel")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"再取消\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_NOT_CANCELABLE"));
    }

    @Test
    void rejectsCancelWithoutReasonAndCancelOfVerifiedPlan() throws Exception {
        long planId = createNormalPlan("MAKING", 4, makerId);

        mockMvc.perform(post("/api/production-plans/" + planId + "/cancel")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='reason')]").isNotEmpty());

        mockMvc.perform(post("/api/production-plans/" + planId + "/verify")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completedQuantity\":4,\"qualifiedQuantity\":4,\"reworkQuantity\":0,"
                                + "\"scrapQuantity\":0}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/production-plans/" + planId + "/cancel")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"已核验不可取消\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_NOT_CANCELABLE"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM production_plans WHERE id = ?", String.class, planId)).isEqualTo("VERIFIED");
    }

    // ---------- 工具 ----------

    /** 核验一条捏毛装袋计划（合格 + 返工 + 报废），返回核验 id。 */
    private long verifyPackingBagPlan(int qualified, int rework, int scrap) throws Exception {
        long planId = createNormalPlan("PACKING_BAG", 10, packerId);
        var result = mockMvc.perform(post("/api/production-plans/" + planId + "/verify")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"completedQuantity":10,"qualifiedQuantity":%d,"reworkQuantity":%d,
                                 "scrapQuantity":%d}
                                """.formatted(qualified, rework, scrap)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private long createNormalPlan(String node, int quantity, long employeeId) throws Exception {
        var created = mockMvc.perform(post("/api/production-plans")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"planType":"NORMAL","orderItemId":%d,"node":"%s","planDate":"2026-09-25",
                                 "employeeId":%d,"quantity":%d}
                                """.formatted(orderItemId, node, employeeId, quantity)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions createReworkSource(long verificationId,
                                                                                 String targetNode,
                                                                                 int quantity, String reason)
            throws Exception {
        return mockMvc.perform(post("/api/rework-sources")
                .cookie(sessionCookie).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"verificationId":%d,"targetNode":"%s","quantity":%d,"reason":%s}
                        """.formatted(verificationId, targetNode, quantity,
                        reason == null ? "null" : "\"" + reason + "\"")));
    }

    private org.springframework.test.web.servlet.ResultActions createRemakeSource(long verificationId,
                                                                                String startNode,
                                                                                int quantity, String reason)
            throws Exception {
        return mockMvc.perform(post("/api/remake-sources")
                .cookie(sessionCookie).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"verificationId":%d,"startNode":"%s","quantity":%d,"reason":%s}
                        """.formatted(verificationId, startNode, quantity,
                        reason == null ? "null" : "\"" + reason + "\"")));
    }

    private long insertEmployee(String employeeNo, String name, String workTypeCode) {
        jdbcTemplate.update("""
                INSERT INTO employees (employee_no, name, status, version, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, employeeNo, name);
        long id = jdbcTemplate.queryForObject(
                "SELECT id FROM employees WHERE employee_no = ?", Long.class, employeeNo);
        jdbcTemplate.update("""
                INSERT INTO employee_work_types (employee_id, work_type_id, created_at)
                SELECT ?, id, UTC_TIMESTAMP(6) FROM work_types WHERE code = ?
                """, id, workTypeCode);
        return id;
    }

    private int sourceBalance(long sourceId) {
        var value = jdbcTemplate.queryForObject(
                "SELECT total_quantity - arranged_quantity FROM rework_sources WHERE id = ?", Integer.class,
                sourceId);
        return value == null ? 0 : value;
    }

    private int reworkPending() {
        var value = jdbcTemplate.queryForObject(
                "SELECT rework_pending FROM order_item_fulfillment_balances WHERE order_item_id = ?",
                Integer.class, orderItemId);
        return value == null ? 0 : value;
    }

    private int remakePending() {
        var value = jdbcTemplate.queryForObject(
                "SELECT remake_pending FROM order_item_fulfillment_balances WHERE order_item_id = ?",
                Integer.class, orderItemId);
        return value == null ? 0 : value;
    }

    private int makingPlanned() {
        var value = jdbcTemplate.queryForObject(
                "SELECT making_planned FROM order_item_fulfillment_balances WHERE order_item_id = ?",
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

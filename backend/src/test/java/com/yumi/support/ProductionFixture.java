package com.yumi.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段五测试夹具：管理员、客户、缝边种类、商品（含产能）、员工（含工种）、已确认订单。
 * 各生产测试类共用，避免重复 100+ 行 SQL；只创建夹具，不做断言。
 */
public final class ProductionFixture {

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    public ProductionFixture(MockMvc mockMvc, JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder,
                             ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
    }

    public void insertAdmin(String username, String password) {
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", username);
        jdbcTemplate.update("""
                INSERT INTO admin_accounts (username, password_hash, status, version, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, username, passwordEncoder.encode(password));
    }

    public Cookie login(String username, String password) throws Exception {
        var result = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getCookie("YUMI_SESSION");
    }

    public long insertCustomer(String no, String name) {
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, contact, phone, default_recipient,
                    default_recipient_phone, default_region, default_address, version, created_at, updated_at)
                VALUES (?, ?, '联系人', '13900000000', '默认收货人', '13900000001', '华南', '默认收货地址', 0,
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, no, name);
        return jdbcTemplate.queryForObject("SELECT id FROM customers WHERE customer_no = ?", Long.class, no);
    }

    public long insertSeamType(String name, int stdMinutes) {
        jdbcTemplate.update("""
                INSERT INTO seam_types (name, std_minutes, version, created_at, updated_at)
                VALUES (?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, name, stdMinutes);
        return jdbcTemplate.queryForObject("SELECT id FROM seam_types WHERE name = ?", Long.class, name);
    }

    /** 商品：星级/包装/缝边标准分钟与产能（模具数量 × 每日批次数）都在快照里冻结。 */
    public long insertProduct(String no, String name, int starMinutes, int packagingMinutes, long seamTypeId,
                              String seamTypeName, int moldQuantity, int dailyBatchLimit) {
        jdbcTemplate.update("""
                INSERT INTO products (product_no, name, status, star_level_id, star_name, star_std_minutes,
                    packaging_tier_name, packaging_std_minutes, sale_price, weight_g, total_cost,
                    seam_type_id, seam_type_name, seam_std_minutes, seam_unit_cost, seam_fee,
                    mold_quantity, daily_batch_limit, version, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', ?,
                    'TST-档位', ?, 25.0000, 100, 18.1200,
                    ?, ?, 5, 1.2500, 2.0000, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, no, name, starMinutes, packagingMinutes, seamTypeId, seamTypeName, moldQuantity,
                dailyBatchLimit);
        return jdbcTemplate.queryForObject("SELECT id FROM products WHERE product_no = ?", Long.class, no);
    }

    public long insertEmployee(String no, String name, String status, String workTypeCode) {
        jdbcTemplate.update("""
                INSERT INTO employees (employee_no, name, status, version, created_at, updated_at)
                VALUES (?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, no, name, status);
        var id = jdbcTemplate.queryForObject("SELECT id FROM employees WHERE employee_no = ?", Long.class, no);
        jdbcTemplate.update("""
                INSERT INTO employee_work_types (employee_id, work_type_id, created_at)
                VALUES (?, (SELECT id FROM work_types WHERE code = ?), UTC_TIMESTAMP(6))
                """, id, workTypeCode);
        return id;
    }

    public long workTypeId(String code) {
        return jdbcTemplate.queryForObject("SELECT id FROM work_types WHERE code = ?", Long.class, code);
    }

    /** 创建并确认订单，返回按明细行顺序的 order_item id。 */
    public long[] confirmOrder(Cookie sessionCookie, long customerId, String orderDate, int[][] items)
            throws Exception {
        var builder = new StringBuilder();
        for (var item : items) {
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            builder.append("{\"productId\":").append(item[0]).append(",\"quantity\":").append(item[1])
                    .append(",\"seamQuantity\":").append(item[2]).append('}');
        }
        var created = mockMvc.perform(post("/api/orders")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + ",\"orderDate\":\"" + orderDate
                                + "\",\"items\":[" + builder + "]}"))
                .andExpect(status().isCreated())
                .andReturn();
        var orderId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        mockMvc.perform(post("/api/orders/" + orderId + "/confirm")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key()))
                .andExpect(status().isOk());
        return jdbcTemplate.queryForList(
                        "SELECT id FROM order_items WHERE order_id = ? ORDER BY line_no", Long.class, orderId)
                .stream().mapToLong(Long::longValue).toArray();
    }

    /** 创建生产任务（正常来源），返回任务视图 data 节点。 */
    public JsonNode createTask(Cookie sessionCookie, String taskDate, long employeeId, long workTypeId,
                               String taskType, String itemsJson) throws Exception {
        var created = mockMvc.perform(post("/api/production-tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskDate":"%s","employeeId":%d,"workTypeId":%d,"taskType":"%s",
                                 "items":[%s]}
                                """.formatted(taskDate, employeeId, workTypeId, taskType, itemsJson)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).path("data");
    }

    public String orderSourceItem(long orderItemId, int quantity) {
        return "{\"orderItemId\":%d,\"plannedQuantity\":%d,\"sourceType\":\"ORDER\"}"
                .formatted(orderItemId, quantity);
    }

    public int count(String sql, Object... args) {
        var value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    public static String key() {
        return UUID.randomUUID().toString();
    }
}

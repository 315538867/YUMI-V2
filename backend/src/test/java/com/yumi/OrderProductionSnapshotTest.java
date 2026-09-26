package com.yumi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.production.ProductionNodes;
import com.yumi.production.ProductionSnapshotReader;
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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段五 5.1 订单生产快照：确认时冻结三道工序标准分钟、制品有效工时率、工作日小时数与产能，
 * 生产域只读订单快照，商品与全局设置变更不回溯既有订单。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderProductionSnapshotTest {

    private static final String USERNAME = "prod-snapshot-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    ProductionSnapshotReader snapshotReader;

    private Cookie sessionCookie;
    private long customerId;
    private long productId;
    private long orderItemId;
    private BigDecimal previousWorkdayHours;
    private BigDecimal previousMakingEffectiveHourRate;

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
                VALUES ('TCS001', 'TST-快照客户', '联系人', '13900000000', '默认收货人', '13900000001',
                    '华南', '默认收货地址', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TCS001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO seam_types (name, std_minutes, version, created_at, updated_at)
                VALUES ('TST-快照缝边种类', 5, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        var seamTypeId = jdbcTemplate.queryForObject(
                "SELECT id FROM seam_types WHERE name = 'TST-快照缝边种类'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO products (product_no, name, status, star_level_id, star_name, star_std_minutes,
                    packaging_tier_name, packaging_std_minutes, sale_price, weight_g, total_cost,
                    seam_type_id, seam_type_name, seam_std_minutes, seam_unit_cost, seam_fee,
                    mold_quantity, daily_batch_limit, version, created_at, updated_at)
                VALUES ('TPS001', 'TST-快照商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    'TST-快照档位', 3, 25.0000, 100, 18.1200,
                    ?, 'TST-快照缝边种类', 5, 1.2500, 2.0000, 10, 5, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, seamTypeId);
        productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TPS001'", Long.class);
        previousWorkdayHours = setting("workday_hours");
        previousMakingEffectiveHourRate = setting("making_effective_hour_rate");
        setSetting("workday_hours", "7.500000");
        setSetting("making_effective_hour_rate", "0.600000");

        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
    }

    @AfterEach
    void cleanup() {
        if (previousWorkdayHours != null) {
            setSetting("workday_hours", previousWorkdayHours.toPlainString());
        }
        if (previousMakingEffectiveHourRate != null) {
            setSetting("making_effective_hour_rate", previousMakingEffectiveHourRate.toPlainString());
        }
        var testOrders = "SELECT id FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TCS001')";
        for (var table : new String[]{"fulfillment_entries", "order_item_fulfillment_balances",
                "order_item_snapshots", "order_confirmation_snapshots", "order_items"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id IN (" + testOrders + ")");
        }
        jdbcTemplate.update("DELETE FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TCS001')");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TPS001'");
        jdbcTemplate.update("DELETE FROM seam_types WHERE name = 'TST-快照缝边种类'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TCS001'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    private void setSetting(String key, String value) {
        jdbcTemplate.update("UPDATE catalog_settings SET setting_value = ? WHERE setting_key = ?",
                new BigDecimal(value), key);
    }

    private BigDecimal setting(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT setting_value FROM catalog_settings WHERE setting_key = ?", BigDecimal.class, key);
    }

    private void confirmOrder() throws Exception {
        var created = mockMvc.perform(post("/api/orders")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,"orderDate":"2026-09-24",
                                 "items":[{"productId":%d,"quantity":10,"seamQuantity":4}]}
                                """.formatted(customerId, productId)))
                .andExpect(status().isCreated())
                .andReturn();
        var orderId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        mockMvc.perform(post("/api/orders/" + orderId + "/confirm")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk());
        orderItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ?", Long.class, orderId);
    }

    @Test
    void confirmationFreezesProductionParameters() throws Exception {
        confirmOrder();

        var snapshot = jdbcTemplate.queryForMap(
                "SELECT * FROM order_item_snapshots WHERE order_item_id = ?", orderItemId);
        assertThat(((Number) snapshot.get("star_std_minutes")).intValue()).isEqualTo(5);
        assertThat(((Number) snapshot.get("packaging_std_minutes")).intValue()).isEqualTo(3);
        assertThat(((Number) snapshot.get("seam_std_minutes")).intValue()).isEqualTo(5);
        assertThat((BigDecimal) snapshot.get("making_effective_hour_rate"))
                .isEqualByComparingTo("0.600000");
        assertThat((BigDecimal) snapshot.get("workday_hours")).isEqualByComparingTo("7.5000");
        assertThat(((Number) snapshot.get("mold_quantity")).intValue()).isEqualTo(10);
        assertThat(((Number) snapshot.get("daily_batch_limit")).intValue()).isEqualTo(5);

        var read = snapshotReader.byOrderItem(orderItemId).orElseThrow();
        assertThat(read.productId()).isEqualTo(productId);
        assertThat(read.standardMinutes(ProductionNodes.MAKING)).isEqualTo(5);
        assertThat(read.standardMinutes(ProductionNodes.PACKING_BAG)).isEqualTo(3);
        assertThat(read.standardMinutes(ProductionNodes.SEAM_CUTTING)).isEqualTo(5);
        assertThat(read.standardMinutes(ProductionNodes.SHIPPABLE)).isNull();
        assertThat(read.makingEffectiveHourRate()).isEqualByComparingTo("0.600000");
        assertThat(read.workdayHours()).isEqualByComparingTo("7.5000");
        assertThat(read.dailyMaxCapacity()).isEqualTo(50);
    }

    @Test
    void productionReadsOrderSnapshotAfterProductChange() throws Exception {
        confirmOrder();

        jdbcTemplate.update("""
                UPDATE products SET star_std_minutes = 9, packaging_std_minutes = 7, seam_std_minutes = 11,
                    mold_quantity = 1, daily_batch_limit = 1
                WHERE id = ?
                """, productId);
        setSetting("workday_hours", "9.000000");
        setSetting("making_effective_hour_rate", "0.900000");

        var read = snapshotReader.byOrderItem(orderItemId).orElseThrow();
        assertThat(read.standardMinutes(ProductionNodes.MAKING)).isEqualTo(5);
        assertThat(read.standardMinutes(ProductionNodes.PACKING_BAG)).isEqualTo(3);
        assertThat(read.standardMinutes(ProductionNodes.SEAM_CUTTING)).isEqualTo(5);
        assertThat(read.makingEffectiveHourRate()).isEqualByComparingTo("0.600000");
        assertThat(read.workdayHours()).isEqualByComparingTo("7.5000");
        assertThat(read.moldQuantity()).isEqualTo(10);
        assertThat(read.dailyBatchLimit()).isEqualTo(5);
        assertThat(read.dailyMaxCapacity()).isEqualTo(50);

        var product = jdbcTemplate.queryForMap(
                "SELECT star_std_minutes, mold_quantity FROM products WHERE id = ?", productId);
        assertThat(((Number) product.get("star_std_minutes")).intValue()).isEqualTo(9);
        assertThat(((Number) product.get("mold_quantity")).intValue()).isEqualTo(1);
    }
}

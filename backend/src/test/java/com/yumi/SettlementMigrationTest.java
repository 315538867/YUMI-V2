package com.yumi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务 7.1 收退款迁移断言：三张表、`PA`/`RF` 唯一编号、金额与来源类型检查、
 * 订单款项投影唯一；Hibernate {@code ddl-auto: validate} 由上下文启动保证。
 */
@SpringBootTest
class SettlementMigrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    private long orderId;

    @BeforeEach
    void setup() {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, version, created_at, updated_at)
                VALUES ('TST001', 'TST-结清迁移客户', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TST001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, customer_id, customer_name, status, order_date, recipient_name,
                    recipient_phone, region, address, goods_amount, seam_amount, discount_amount,
                    receivable_amount, goods_cost_amount, seam_cost_amount, cost_amount, profit_amount,
                    version, created_at, updated_at)
                VALUES ('TST0001', ?, 'TST-结清迁移客户', 'CONFIRMED', '2026-09-25', '收货人', '13800000000',
                    '华东', '地址', 100.0000, 0.0000, 0.0000, 100.0000, 60.0000, 0.0000, 60.0000, 40.0000,
                    1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, customerId);
        orderId = jdbcTemplate.queryForObject("SELECT id FROM orders WHERE order_no = 'TST0001'", Long.class);
    }

    @AfterEach
    void cleanup() {
        var testOrders = "SELECT id FROM orders WHERE order_no = 'TST0001'";
        jdbcTemplate.update("DELETE FROM order_settlement_balances WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM refunds WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM payments WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM orders WHERE order_no = 'TST0001'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TST001'");
    }

    @Test
    void createsSettlementTables() {
        for (var table : new String[]{"payments", "refunds", "order_settlement_balances"}) {
            assertThat(tableExists(table)).as("缺少表 " + table).isTrue();
        }
    }

    @Test
    void enforcesNumbersAmountsSourcesAndKeys() {
        assertThat(columnType("payments", "payment_no")).isEqualTo("char(8)");
        assertThat(columnType("payments", "amount")).isEqualTo("decimal(19,4)");
        assertThat(columnType("refunds", "refund_no")).isEqualTo("char(8)");
        assertThat(columnType("refunds", "source_id")).isEqualTo("bigint unsigned");
        assertThat(columnType("order_settlement_balances", "refund_pending_amount")).isEqualTo("decimal(19,4)");

        assertThat(uniqueKeys("payments")).contains("uk_payments_payment_no");
        assertThat(uniqueKeys("refunds")).contains("uk_refunds_refund_no");
        assertThat(uniqueKeys("order_settlement_balances")).contains("uk_order_settlement_balances_order");
        assertThat(indexes("refunds")).contains("idx_refunds_source");
        assertThat(foreignKeys("payments")).contains("fk_payments_order");
        assertThat(foreignKeys("refunds")).contains("fk_refunds_order");
        assertThat(foreignKeys("order_settlement_balances")).contains("fk_order_settlement_balances_order");
    }

    @Test
    void rejectsNonPositiveAmountsBadSourceAndDuplicateNumbers() {
        // 金额必须大于 0
        assertThatThrownBy(() -> insertPayment("PA900001", "0.0000"))
                .as("收款金额必须大于 0")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_payments_amount");
        assertThatThrownBy(() -> insertRefund("RF900001", "AFTER_SALES", "-1.0000"))
                .as("退款金额必须大于 0")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_refunds_amount");
        // 来源类型受约束
        assertThatThrownBy(() -> insertRefund("RF900002", "UNKNOWN", "1.0000"))
                .as("退款来源类型受约束")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_refunds_source_type");

        insertPayment("PA900002", "10.0000");
        assertThatThrownBy(() -> insertPayment("PA900002", "10.0000"))
                .as("收款编号唯一")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_payments_payment_no");
        insertRefund("RF900003", "ORDER_CHANGE", "1.0000");
        assertThatThrownBy(() -> insertRefund("RF900003", "ORDER_CHANGE", "1.0000"))
                .as("退款编号唯一")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_refunds_refund_no");

        // 订单款项投影每单唯一
        insertSettlement();
        assertThatThrownBy(this::insertSettlement)
                .as("每单一条款项投影")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_order_settlement_balances_order");
    }

    // ---------- 工具 ----------

    private void insertPayment(String paymentNo, String amount) {
        jdbcTemplate.update("""
                INSERT INTO payments (payment_no, order_id, amount, business_date, method, created_at, updated_at)
                VALUES (?, ?, ?, '2026-09-25', '现金', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, paymentNo, orderId, amount);
    }

    private void insertRefund(String refundNo, String sourceType, String amount) {
        jdbcTemplate.update("""
                INSERT INTO refunds (refund_no, order_id, amount, business_date, method, reason, source_type,
                    source_id, created_at, updated_at)
                VALUES (?, ?, ?, '2026-09-25', '现金', '测试退款', ?, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, refundNo, orderId, amount, sourceType);
    }

    private void insertSettlement() {
        jdbcTemplate.update("""
                INSERT INTO order_settlement_balances (order_id, effective_receivable_amount, version,
                    created_at, updated_at)
                VALUES (?, 100.0000, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId);
    }

    private boolean tableExists(String tableName) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) > 0 FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                Boolean.class, tableName));
    }

    private String columnType(String tableName, String columnName) {
        return jdbcTemplate.queryForObject(
                "SELECT column_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class, tableName, columnName);
    }

    private java.util.List<String> uniqueKeys(String tableName) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND non_unique = 0 "
                        + "AND index_name <> 'PRIMARY'",
                String.class, tableName);
    }

    private java.util.List<String> indexes(String tableName) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                String.class, tableName);
    }

    private java.util.List<String> foreignKeys(String tableName) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT constraint_name FROM information_schema.table_constraints "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND constraint_type = 'FOREIGN KEY'",
                String.class, tableName);
    }
}

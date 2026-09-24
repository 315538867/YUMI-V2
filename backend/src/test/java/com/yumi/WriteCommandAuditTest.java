package com.yumi;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 1.7 写命令审计 HTTP 黑盒测试：成功/失败写命令记录操作人、请求 ID、幂等键、
 * 服务端时间与结果；失败事务不留下成功事实但保留安全审计；审计上下文可被写命令读取。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WriteCommandAuditTest {

    private static final String USERNAME = "audit-test-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String COUNTER = "/api/__test/fixture/counter";
    private static final String COUNTER_FAIL = "/api/__test/fixture/counter-then-fail";
    private static final String CONTEXT = "/api/__test/fixture/audit-context";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PasswordEncoder passwordEncoder;

    private Cookie sessionCookie;

    @BeforeEach
    void login() throws Exception {
        jdbcTemplate.update("DELETE FROM idempotency_records WHERE idempotency_key LIKE 'audit-%'");
        jdbcTemplate.update("DELETE FROM audit_logs WHERE request_id LIKE 'audit-%'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
        jdbcTemplate.update("""
                INSERT INTO admin_accounts (
                    username, password_hash, status, version, created_at, updated_at
                ) VALUES (?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, USERNAME, passwordEncoder.encode(PASSWORD));
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "audit-login-setup")
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
    }

    private java.util.Map<String, Object> auditRow(String requestId) {
        var rows = jdbcTemplate.queryForList(
                "SELECT admin_username, request_id, idempotency_key, business_no, http_method, path, "
                        + "result, response_status, error_code, occurred_at "
                        + "FROM audit_logs WHERE request_id = ? ORDER BY id DESC", requestId);
        assertThat(rows).as("request_id=%s 的审计记录", requestId).isNotEmpty();
        return rows.get(0);
    }

    @Test
    void successfulWriteRecordsAuditWithContext() throws Exception {
        mockMvc.perform(post(COUNTER)
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "audit-req-1")
                        .header("Idempotency-Key", "audit-key-1")
                        .content("{\"note\":\"ok\"}"))
                .andExpect(status().isCreated());

        var row = auditRow("audit-req-1");
        assertThat(row.get("admin_username")).isEqualTo(USERNAME);
        assertThat(row.get("idempotency_key")).isEqualTo("audit-key-1");
        assertThat(row.get("http_method")).isEqualTo("POST");
        assertThat(row.get("path")).isEqualTo(COUNTER);
        assertThat(row.get("result")).isEqualTo("SUCCESS");
        assertThat(((Number) row.get("response_status")).intValue()).isEqualTo(201);
        assertThat(row.get("business_no")).isNull();
        assertThat(row.get("occurred_at")).isNotNull();
    }

    @Test
    void failedTransactionLeavesNoSuccessFactButKeepsSecurityAudit() throws Exception {
        var factsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM number_sequences WHERE sequence_key LIKE 'fixture:%'", Integer.class);

        mockMvc.perform(post(COUNTER_FAIL)
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "audit-req-2")
                        .header("Idempotency-Key", "audit-key-2")
                        .content("{}"))
                .andExpect(status().isInternalServerError());

        var factsAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM number_sequences WHERE sequence_key LIKE 'fixture:%'", Integer.class);
        assertThat(factsAfter).as("失败事务必须回滚，不留成功事实").isEqualTo(factsBefore);

        var row = auditRow("audit-req-2");
        assertThat(row.get("admin_username")).isEqualTo(USERNAME);
        assertThat(row.get("idempotency_key")).isEqualTo("audit-key-2");
        assertThat(row.get("result")).isEqualTo("FAILURE");
        assertThat(row.get("error_code")).isEqualTo("INTERNAL_ERROR");
        assertThat(((Number) row.get("response_status")).intValue()).isEqualTo(500);
    }

    @Test
    void failedLoginAttemptIsAuditedAsSecurityEvent() throws Exception {
        mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "audit-req-3")
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));

        var row = auditRow("audit-req-3");
        assertThat(row.get("path")).isEqualTo("/api/session");
        assertThat(row.get("result")).isEqualTo("FAILURE");
        assertThat(row.get("error_code")).isEqualTo("AUTH_INVALID");
        assertThat(row.get("admin_username")).isNull();
        assertThat(((Number) row.get("response_status")).intValue()).isEqualTo(401);
    }

    @Test
    void auditContextExposesOperatorRequestAndIdempotencyKey() throws Exception {
        mockMvc.perform(get(CONTEXT)
                        .cookie(sessionCookie)
                        .header("X-Request-Id", "audit-req-4")
                        .header("Idempotency-Key", "audit-key-4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.adminUsername").value(USERNAME))
                .andExpect(jsonPath("$.data.requestId").value("audit-req-4"))
                .andExpect(jsonPath("$.data.idempotencyKey").value("audit-key-4"))
                .andExpect(jsonPath("$.data.serverTime").isString());
    }
}

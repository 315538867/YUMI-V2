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
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统一响应信封（design.md §2，2026-09-24 用户拍板 A 案）：
 * 所有 API 响应都是 {code, message, fieldErrors, requestId, data}；
 * 成功 code=OK、data=业务负载；失败 code=稳定错误码、data=null；204 无体。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SuccessEnvelopeContractTest {

    private static final String USERNAME = "envelope-test-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PasswordEncoder passwordEncoder;

    private Cookie sessionCookie;

    @BeforeEach
    void login() throws Exception {
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
        jdbcTemplate.update("""
                INSERT INTO admin_accounts (
                    username, password_hash, status, version, created_at, updated_at
                ) VALUES (?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, USERNAME, passwordEncoder.encode(PASSWORD));
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "envelope-login-setup")
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.username").value(USERNAME))
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
    }

    @Test
    void loginSuccessUsesEnvelopeWithData() throws Exception {
        // setup 已断言成功信封；此处确认失败体 data 为 null
        mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nope\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void currentSessionWrappedInEnvelope() throws Exception {
        mockMvc.perform(get("/api/session").cookie(sessionCookie)
                        .header("X-Request-Id", "envelope-req-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value(""))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.requestId").value("envelope-req-1"))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.username").value(USERNAME))
                .andExpect(header().string("X-Request-Id", "envelope-req-1"));
    }

    @Test
    void logoutNoContentHasNoBody() throws Exception {
        mockMvc.perform(delete("/api/session").cookie(sessionCookie))
                .andExpect(status().isNoContent())
                .andExpect(result -> org.assertj.core.api.Assertions
                        .assertThat(result.getResponse().getContentAsString()).isEmpty());
    }

    @Test
    void protectedGetStillUnauthorizedWithNullData() throws Exception {
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void actuatorHealthWrappedInEnvelope() throws Exception {
        mockMvc.perform(get("/api/actuator/health/liveness").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("UP"));
    }

    @Test
    void requestIdAlwaysPresentOnEnvelope() throws Exception {
        mockMvc.perform(get("/api/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.requestId").value(not(blankOrNullString())))
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }
}

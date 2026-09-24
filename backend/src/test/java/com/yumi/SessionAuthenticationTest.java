package com.yumi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SessionAuthenticationTest {

    private static final String USERNAME = "session-test-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    @Autowired
    MockMvc mockMvc;

    @Autowired
    org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void createTestAdmin() {
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
        jdbcTemplate.update("""
                INSERT INTO admin_accounts (
                    username, password_hash, status, version, created_at, updated_at
                ) VALUES (?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, USERNAME, passwordEncoder.encode(PASSWORD));
    }

    @AfterEach
    void removeTestAdmin() {
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    @Test
    void rejectsCurrentSessionWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.requestId").isString());
    }

    @Test
    void rejectsProtectedApiWithoutAuthenticationWithUnifiedError() throws Exception {
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.requestId").isString());
    }

    @Test
    void rejectsInvalidPasswordWithoutRevealingAccountState() throws Exception {
        mockMvc.perform(post("/api/session")
                        .contentType("application/json")
                        .content("""
                                {"username":"session-test-admin","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.requestId").isString());
    }

    @Test
    void createsSecureHttpOnlySessionCookieForValidCredentials() throws Exception {
        mockMvc.perform(post("/api/session")
                        .contentType("application/json")
                        .content("""
                                {"username":"session-test-admin","password":"CorrectHorseBatteryStaple!"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(USERNAME))
                .andExpect(cookie().exists("YUMI_SESSION"))
                .andExpect(cookie().httpOnly("YUMI_SESSION", true))
                .andExpect(cookie().secure("YUMI_SESSION", true))
                .andExpect(cookie().path("YUMI_SESSION", "/"));
    }

    @Test
    void returnsCurrentAdminAndDeletesSession() throws Exception {
        var login = mockMvc.perform(post("/api/session")
                        .contentType("application/json")
                        .content("""
                                {"username":"session-test-admin","password":"CorrectHorseBatteryStaple!"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        var sessionCookie = login.getResponse().getCookie("YUMI_SESSION");

        mockMvc.perform(get("/api/session").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(USERNAME));

        mockMvc.perform(delete("/api/session").cookie(sessionCookie))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("YUMI_SESSION", 0));

        mockMvc.perform(get("/api/session").cookie(sessionCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }
}

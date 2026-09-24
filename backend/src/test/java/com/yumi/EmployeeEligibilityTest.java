package com.yumi;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 2.8 员工工种资格黑盒测试：在职有工种 200、离职或缺工种 409 EMPLOYEE_NOT_ELIGIBLE、
 * workType 校验 400、未登录 401。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmployeeEligibilityTest {

    private static final String USERNAME = "e-test-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String BASE = "/api/employees";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PasswordEncoder passwordEncoder;

    private Cookie sessionCookie;

    @BeforeEach
    void login() throws Exception {
        jdbcTemplate.update("DELETE FROM idempotency_records WHERE idempotency_key LIKE 'emp-%'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
        jdbcTemplate.update("""
                INSERT INTO admin_accounts (
                    username, password_hash, status, version, created_at, updated_at
                ) VALUES (?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, USERNAME, passwordEncoder.encode(PASSWORD));
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                Map.of("username", USERNAME, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
    }

    private long createWith(String... workTypes) throws Exception {
        var result = mockMvc.perform(post(BASE)
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "emp-elig-" + UUID.randomUUID())
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "name", "资格测试", "firstHireDate", "2026-01-01",
                                "workTypes", List.of(workTypes)))))
                .andExpect(status().isCreated())
                .andReturn();
        var body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return objectMapper.readTree(body).get("data").get("id").asLong();
    }

    @Test
    void activeEmployeeWithWorkTypeIsEligible() throws Exception {
        var id = createWith("MAKING", "SEAM_CUTTING");
        mockMvc.perform(get(BASE + "/" + id + "/eligibility").cookie(sessionCookie).param("workType", "MAKING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.eligible").value(true))
                .andExpect(jsonPath("$.data.employeeNo").value(org.hamcrest.Matchers.matchesPattern("E\\d{5}")))
                .andExpect(jsonPath("$.data.name").value("资格测试"))
                .andExpect(jsonPath("$.data.workType").value("MAKING"));
    }

    @Test
    void activeEmployeeWithoutWorkTypeIsNotEligible() throws Exception {
        var id = createWith("MAKING");
        mockMvc.perform(get(BASE + "/" + id + "/eligibility").cookie(sessionCookie).param("workType", "SEAM_CUTTING"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMPLOYEE_NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.requestId").isString());
    }

    @Test
    void leftEmployeeIsNotEligibleEvenWithWorkType() throws Exception {
        var id = createWith("MAKING");
        mockMvc.perform(post(BASE + "/" + id + "/leave")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "emp-elig-" + UUID.randomUUID())
                        .content(objectMapper.writeValueAsBytes(Map.of("reason", "离职", "date", "2026-02-02"))))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/" + id + "/eligibility").cookie(sessionCookie).param("workType", "MAKING"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMPLOYEE_NOT_ELIGIBLE"));
    }

    @Test
    void missingOrInvalidWorkTypeReturnsValidationInvalid() throws Exception {
        var id = createWith("MAKING");
        mockMvc.perform(get(BASE + "/" + id + "/eligibility").cookie(sessionCookie).param("workType", "电焊"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("workType"));
        mockMvc.perform(get(BASE + "/" + id + "/eligibility").cookie(sessionCookie))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("workType"));
    }

    @Test
    void eligibilityRequiresAuthentication() throws Exception {
        mockMvc.perform(get(BASE + "/1/eligibility").param("workType", "MAKING"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }
}

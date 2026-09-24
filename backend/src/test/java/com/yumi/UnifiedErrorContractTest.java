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

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 1.6 统一错误契约 HTTP 黑盒测试：固定 {code,message,fieldErrors,requestId} JSON 形状、
 * 各错误族状态码映射、请求 ID 传播与写命令幂等键必填。
 */
@SpringBootTest
@AutoConfigureMockMvc
class UnifiedErrorContractTest {

    private static final String USERNAME = "error-contract-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String BASE = "/api/__test/fixture";

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
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
    }

    @Test
    void stateFamilyReturnsConflictWithEnvelope() throws Exception {
        mockMvc.perform(get(BASE + "/state").cookie(sessionCookie))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_NOT_EDITABLE"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.requestId").value(not(blankOrNullString())))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void quantityFamilyCarriesFieldLocation() throws Exception {
        mockMvc.perform(get(BASE + "/quantity").cookie(sessionCookie))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUANTITY_BELOW_SHIPPED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("quantity"))
                .andExpect(jsonPath("$.fieldErrors[0].message").isString());
    }

    @Test
    void sourceFamilyReturnsConflict() throws Exception {
        mockMvc.perform(get(BASE + "/source").cookie(sessionCookie))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_ALREADY_CONSUMED"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void financeFamilyReturnsConflict() throws Exception {
        mockMvc.perform(get(BASE + "/finance").cookie(sessionCookie))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_DRAFT_FORBIDDEN"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void optimisticLockingMapsToConflictVersion() throws Exception {
        mockMvc.perform(get(BASE + "/conflict").cookie(sessionCookie))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT_VERSION"))
                .andExpect(jsonPath("$.requestId").isString());
    }

    @Test
    void missingResourceMapsToNotFoundEnvelope() throws Exception {
        mockMvc.perform(get(BASE + "/missing").cookie(sessionCookie))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void beanValidationReturnsValidationInvalidWithFieldErrors() throws Exception {
        mockMvc.perform(post(BASE + "/validated")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "contract-validation-1")
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
                .andExpect(jsonPath("$.fieldErrors[0].message").isString())
                .andExpect(jsonPath("$.requestId").isString());
    }

    @Test
    void malformedJsonReturnsValidationInvalid() throws Exception {
        mockMvc.perform(post(BASE + "/validated")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "contract-malformed-1")
                        .content("not-a-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.requestId").isString());
    }

    @Test
    void inboundRequestIdIsEchoedInBodyAndHeader() throws Exception {
        mockMvc.perform(get(BASE + "/state")
                        .cookie(sessionCookie)
                        .header("X-Request-Id", "contract-req-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.requestId").value("contract-req-1"))
                .andExpect(header().string("X-Request-Id", "contract-req-1"));
    }

    @Test
    void writeWithoutIdempotencyKeyIsRejected() throws Exception {
        mockMvc.perform(post(BASE + "/validated")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"valid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("Idempotency-Key"))
                .andExpect(jsonPath("$.requestId").isString());
    }

    @Test
    void unauthenticatedFixtureRequestGetsAuthRequiredEnvelope() throws Exception {
        mockMvc.perform(get(BASE + "/state"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.requestId").isString());
    }

    @Test
    void invalidInboundRequestIdIsReplaced() throws Exception {
        mockMvc.perform(get(BASE + "/state")
                        .cookie(sessionCookie)
                        .header("X-Request-Id", "bad id with spaces"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.requestId").value(not("bad id with spaces")))
                .andExpect(jsonPath("$.requestId").value(not(blankOrNullString())));
    }
}

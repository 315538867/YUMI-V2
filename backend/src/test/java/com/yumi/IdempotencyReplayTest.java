package com.yumi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.fixture.FixtureController;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 1.6 幂等存储与重复请求返回 HTTP 黑盒测试：
 * 相同幂等键+相同请求返回首次结果且事实只写一份；同键异请求返回 CONFLICT_IDEMPOTENCY；
 * 5xx 不落库可用同键重试；写命令缺少 Idempotency-Key 被拒绝。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyReplayTest {

    private static final String USERNAME = "idempotency-test-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String COUNTER = "/api/__test/fixture/counter";
    private static final String FLAKY = "/api/__test/fixture/flaky";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    ObjectMapper objectMapper;

    private Cookie sessionCookie;

    @BeforeEach
    void login() throws Exception {
        jdbcTemplate.update("DELETE FROM idempotency_records WHERE idempotency_key LIKE 'replay-key-%'");
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
        FixtureController.resetFlaky();
    }

    private int fixtureFactCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM number_sequences WHERE sequence_key LIKE 'fixture:%'",
                Integer.class);
    }

    private JsonNode body(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void sameKeyAndSameRequestReplaysFirstResultWithSingleFact() throws Exception {
        var factsBefore = fixtureFactCount();

        var first = mockMvc.perform(post(COUNTER)
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "replay-key-1")
                        .content("{\"note\":\"first\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        var firstToken = body(first).get("data").get("token").asText();

        var second = mockMvc.perform(post(COUNTER)
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "replay-key-1")
                        .content("{\"note\":\"first\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Idempotency-Replayed", "true"))
                .andReturn();
        var secondToken = body(second).get("data").get("token").asText();

        assertThat(secondToken).isEqualTo(firstToken);
        assertThat(fixtureFactCount()).isEqualTo(factsBefore + 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = ?",
                Integer.class, "replay-key-1")).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentRequestReturnsConflictIdempotency() throws Exception {
        mockMvc.perform(post(COUNTER)
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "replay-key-2")
                        .content("{\"note\":\"original\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post(COUNTER)
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "replay-key-2")
                        .content("{\"note\":\"different\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT_IDEMPOTENCY"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.requestId").isString());
    }

    @Test
    void serverErrorIsNotStoredAndSameKeyCanRetry() throws Exception {
        var factsBefore = fixtureFactCount();

        mockMvc.perform(post(FLAKY)
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "replay-key-3")
                        .content("{}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        mockMvc.perform(post(FLAKY)
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "replay-key-3")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("flaky-ok"));

        assertThat(fixtureFactCount()).isEqualTo(factsBefore);
        // 首次 500 未落库（否则第二次会重放 500 而非执行成功），只有重试成功的 200 被记录。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = ?",
                Integer.class, "replay-key-3")).isEqualTo(1);
    }

    @Test
    void businessConflictOutcomeIsReplayedForSameKey() throws Exception {
        var conflictWrite = "/api/__test/fixture/conflict-write";

        mockMvc.perform(post(conflictWrite)
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "replay-key-4")
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_NOT_EDITABLE"));

        mockMvc.perform(post(conflictWrite)
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "replay-key-4")
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(header().string("X-Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.code").value("STATE_NOT_EDITABLE"));
    }

    @Test
    void getRequestsDoNotRequireIdempotencyKey() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/__test/fixture/state")
                        .cookie(sessionCookie))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_NOT_EDITABLE"));
    }
}

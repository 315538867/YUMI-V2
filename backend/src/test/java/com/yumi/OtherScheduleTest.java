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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 5.13：其他排班的创建、一次性工时核验、取消与工时更正。
 * 口径见 `production-management`「其他排班只能一次工时核验」与施工文档 §3.8–§3.10/§4.7：
 * 分钟 0–59、总分钟 = 小时 × 60 + 分钟 且大于 0，**不产生商品、库存或订单履约事实**。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OtherScheduleTest {

    private static final String USERNAME = "other-schedule-admin";
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
    private long workerId;
    private long resignedId;

    @BeforeEach
    void setup() throws Exception {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO admin_accounts (username, password_hash, status, version, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, USERNAME, passwordEncoder.encode(PASSWORD));
        workerId = insertEmployee("TOS001", "TST-杂活员工", "ACTIVE");
        resignedId = insertEmployee("TOS002", "TST-离职员工", "RESIGNED");
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
    }

    @AfterEach
    void cleanup() {
        var testSchedules = "SELECT id FROM other_schedules WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no IN ('TOS001', 'TOS002'))";
        jdbcTemplate.update("DELETE FROM other_schedule_time_corrections WHERE schedule_id IN ("
                + testSchedules + ")");
        jdbcTemplate.update("DELETE FROM other_schedule_verifications WHERE schedule_id IN ("
                + testSchedules + ")");
        jdbcTemplate.update("DELETE FROM other_schedules WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no IN ('TOS001', 'TOS002'))");
        jdbcTemplate.update("DELETE FROM employees WHERE employee_no IN ('TOS001', 'TOS002')");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    @Test
    void createsScheduleWithHoursAndMinutesWithoutProducingGoodsFacts() throws Exception {
        var created = createSchedule(1, 30, workerId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.scheduleNo").value(org.hamcrest.Matchers.startsWith("OS")))
                .andExpect(jsonPath("$.data.hours").value(1))
                .andExpect(jsonPath("$.data.minutes").value(30))
                .andExpect(jsonPath("$.data.totalMinutes").value(90))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.employeeName").value("TST-杂活员工"))
                .andReturn();
        long scheduleId = dataId(created);

        // 不产生商品/库存/订单履约事实
        assertThat(count("production_plans")).isZero();
        assertThat(count("fulfillment_entries")).isZero();
        assertThat(count("inventory_movements")).isZero();

        // 分钟必须 0–59；总分钟必须大于 0
        createSchedule(1, 60, workerId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='minutes')]").isNotEmpty());
        createSchedule(0, 0, workerId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='hours')]").isNotEmpty());

        // 离职员工不可排班
        createSchedule(2, 0, resignedId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMPLOYEE_NOT_ELIGIBLE"));

        mockMvc.perform(get("/api/other-schedules/" + scheduleId).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalMinutes").value(90))
                // 未核验时没有工时事实，有效工时为空（不拿计划值冒充事实）
                .andExpect(jsonPath("$.data.effectiveMinutes").doesNotExist())
                .andExpect(jsonPath("$.data.verifiedMinutes").doesNotExist())
                .andExpect(jsonPath("$.data.corrected").value(false));
    }

    @Test
    void verifiesOnceAndCorrectsWithoutTouchingOriginalVerification() throws Exception {
        long scheduleId = dataId(createSchedule(1, 30, workerId).andExpect(status().isCreated()).andReturn());

        mockMvc.perform(post("/api/other-schedules/" + scheduleId + "/verify")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hours\":2,\"minutes\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VERIFIED"))
                .andExpect(jsonPath("$.data.verifiedMinutes").value(120))
                .andExpect(jsonPath("$.data.effectiveMinutes").value(120));

        // 只能核验一次
        mockMvc.perform(post("/api/other-schedules/" + scheduleId + "/verify")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hours\":2,\"minutes\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_NOT_CANCELABLE"));
        assertThat(count("other_schedule_verifications")).isEqualTo(1);

        // 工时更正：追加事实，原核验不变，有效工时取最新更正
        mockMvc.perform(post("/api/other-schedules/" + scheduleId + "/corrections")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hours\":1,\"minutes\":45,\"reason\":\"实际提前收工\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verifiedMinutes").value(120))
                .andExpect(jsonPath("$.data.effectiveMinutes").value(105))
                .andExpect(jsonPath("$.data.corrected").value(true));
        var correction = jdbcTemplate.queryForMap("""
                SELECT before_total_minutes, after_total_minutes, reason
                FROM other_schedule_time_corrections WHERE schedule_id = ?
                """, scheduleId);
        assertThat(((Number) correction.get("before_total_minutes")).intValue()).isEqualTo(120);
        assertThat(((Number) correction.get("after_total_minutes")).intValue()).isEqualTo(105);
        assertThat(correction.get("reason")).isEqualTo("实际提前收工");

        // 更正原因必填
        mockMvc.perform(post("/api/other-schedules/" + scheduleId + "/corrections")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hours\":2,\"minutes\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='reason')]").isNotEmpty());
    }

    @Test
    void cancelsPendingScheduleWithReasonAndRejectsVerified() throws Exception {
        long scheduleId = dataId(createSchedule(1, 0, workerId).andExpect(status().isCreated()).andReturn());

        mockMvc.perform(post("/api/other-schedules/" + scheduleId + "/cancel")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='reason')]").isNotEmpty());

        mockMvc.perform(post("/api/other-schedules/" + scheduleId + "/cancel")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"临时取消\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelReason").value("临时取消"));

        // 已核验排班不可取消
        long verifiedId = dataId(createSchedule(1, 0, workerId).andExpect(status().isCreated()).andReturn());
        mockMvc.perform(post("/api/other-schedules/" + verifiedId + "/verify")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hours\":1,\"minutes\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/other-schedules/" + verifiedId + "/cancel")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"试图取消\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_NOT_CANCELABLE"));

        // 列表按员工与状态筛选
        mockMvc.perform(get("/api/other-schedules").cookie(sessionCookie)
                        .param("employeeId", String.valueOf(workerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
        mockMvc.perform(get("/api/other-schedules").cookie(sessionCookie).param("status", "CANCELLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // ---------- 工具 ----------

    private org.springframework.test.web.servlet.ResultActions createSchedule(int hours, int minutes,
                                                                             long employeeId) throws Exception {
        return mockMvc.perform(post("/api/other-schedules")
                .cookie(sessionCookie).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"scheduleDate":"2026-09-25","employeeId":%d,"hours":%d,"minutes":%d,"note":"打包杂活"}
                        """.formatted(employeeId, hours, minutes)));
    }

    private long insertEmployee(String employeeNo, String name, String status) {
        jdbcTemplate.update("""
                INSERT INTO employees (employee_no, name, status, version, created_at, updated_at)
                VALUES (?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, employeeNo, name, status);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM employees WHERE employee_no = ?", Long.class, employeeNo);
    }

    private long dataId(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.path("data").path("id").asLong();
    }

    private int count(String table) {
        var value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private String key() {
        return java.util.UUID.randomUUID().toString();
    }
}

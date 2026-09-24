package com.yumi;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 2.7 员工 API 黑盒测试：创建（编号/HIRE 事件/工种落库）、乐观锁编辑、
 * 离职与重入职状态机、变更日志、工种校验与写命令幂等键必填。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmployeeApiTest {

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

    // ---------- helpers ----------

    private String key() {
        return "emp-api-" + UUID.randomUUID();
    }

    private JsonNode create(String name, String firstHireDate, List<String> workTypes) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("name", name);
        body.put("phone", "13800001111");
        body.put("firstHireDate", firstHireDate);
        body.put("note", "首批员工");
        body.put("workTypes", workTypes);
        var result = mockMvc.perform(post(BASE)
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content(objectMapper.writeValueAsBytes(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return dataOf(result);
    }

    private com.fasterxml.jackson.databind.JsonNode dataOf(org.springframework.test.web.servlet.MvcResult result)
            throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
    }

    private long createAndId(String name, String firstHireDate, List<String> workTypes) throws Exception {
        return create(name, firstHireDate, workTypes).get("id").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions leave(long id, String reason, String date)
            throws Exception {
        return mockMvc.perform(post(BASE + "/" + id + "/leave")
                .cookie(sessionCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key())
                .content(objectMapper.writeValueAsBytes(Map.of("reason", reason, "date", date))));
    }

    private List<String> workTypesOf(long employeeId) {
        return jdbcTemplate.queryForList(
                "SELECT wt.code FROM employee_work_types ewt JOIN work_types wt ON wt.id = ewt.work_type_id "
                        + "WHERE ewt.employee_id = ? ORDER BY ewt.id", String.class, employeeId);
    }

    private JsonNode latestChangeLog(String businessNo) throws Exception {
        var row = jdbcTemplate.queryForMap(
                "SELECT before_json, after_json, reason, admin_username, request_id FROM master_data_change_logs "
                        + "WHERE entity_type = 'EMPLOYEE' AND business_no = ? ORDER BY id DESC LIMIT 1", businessNo);
        var node = objectMapper.createObjectNode();
        row.forEach((k, v) -> node.putPOJO(k, v));
        return node;
    }

    // ---------- tests ----------

    @Test
    void createsEmployeeWithEnvelopeHireEventAndWorkTypes() throws Exception {
        var data = create("张三-员工API", "2026-01-02", List.of("MAKING", "SEAM_CUTTING"));

        assertThat(data.get("employeeNo").asText()).matches("E\\d{5}");
        assertThat(data.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(data.get("firstHireDate").asText()).isEqualTo("2026-01-02");
        assertThat(data.get("workTypes")).isNotNull();
        var id = data.get("id").asLong();
        assertThat(workTypesOf(id)).containsExactlyInAnyOrder("MAKING", "SEAM_CUTTING");

        var event = jdbcTemplate.queryForMap(
                "SELECT event_type, event_date FROM employee_employment_events WHERE employee_id = ? "
                        + "ORDER BY id LIMIT 1", id);
        assertThat(event.get("event_type")).isEqualTo("HIRE");
        assertThat(String.valueOf(event.get("event_date"))).isEqualTo("2026-01-02");

        // 列表与详情
        mockMvc.perform(get(BASE).cookie(sessionCookie)
                        .param("status", "ACTIVE").param("name", "张三-员工API"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data[?(@.id == " + id + ")]").exists());
        mockMvc.perform(get(BASE + "/" + id).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.employeeNo").value(data.get("employeeNo").asText()))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.workTypes[?(@.code == 'MAKING')]").exists());
    }

    @Test
    void patchReplacesNameAndWorkTypesAndRejectsStaleVersion() throws Exception {
        var created = create("李四-员工API", "2026-02-02", List.of("MAKING"));
        var id = created.get("id").asLong();
        var oldVersion = created.get("version").asLong();

        var patchRid = "emp-api-patch-" + UUID.randomUUID();
        mockMvc.perform(patch(BASE + "/" + id)
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .header("X-Request-Id", patchRid)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "version", oldVersion, "name", "李四改",
                                "workTypes", List.of("OTHER", "PACKING_BAG"),
                                "status", "LEFT", "firstHireDate", "2020-01-01"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.name").value("李四改"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.firstHireDate").value("2026-02-02"))
                .andExpect(jsonPath("$.data.version").value(oldVersion + 1));
        assertThat(workTypesOf(id)).containsExactlyInAnyOrder("OTHER", "PACKING_BAG");

        var log = latestChangeLog(created.get("employeeNo").asText());
        assertThat(log.get("admin_username").asText()).isEqualTo(USERNAME);
        assertThat(log.get("request_id").asText()).isEqualTo(patchRid);

        // 旧 version 再改 → 409 CONFLICT_VERSION
        mockMvc.perform(patch(BASE + "/" + id)
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "version", oldVersion, "name", "再改"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT_VERSION"));
    }

    @Test
    void leaveThenCreateDoesNotRecycleEmployeeNumber() throws Exception {
        var first = create("王五-员工API", "2026-03-03", List.of("MAKING"));
        leave(first.get("id").asLong(), "回老家", "2026-04-04").andExpect(status().isOk());
        var second = create("赵六-员工API", "2026-03-03", List.of("MAKING"));

        var n1 = Integer.parseInt(first.get("employeeNo").asText().substring(1));
        var n2 = Integer.parseInt(second.get("employeeNo").asText().substring(1));
        assertThat(n2).isEqualTo(n1 + 1);
    }

    @Test
    void leaveSetsStatusAppendsEventAndWritesChangeLog() throws Exception {
        var created = create("钱七-员工API", "2026-05-05", List.of("SEAM_CUTTING"));
        var id = created.get("id").asLong();
        var rid = "emp-api-leave-" + UUID.randomUUID();
        mockMvc.perform(post(BASE + "/" + id + "/leave")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .header("X-Request-Id", rid)
                        .content(objectMapper.writeValueAsBytes(Map.of("reason", "个人原因", "date", "2026-06-06"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("LEFT"));

        var event = jdbcTemplate.queryForMap(
                "SELECT event_type, event_date, reason FROM employee_employment_events "
                        + "WHERE employee_id = ? AND event_type = 'LEAVE'", id);
        assertThat(String.valueOf(event.get("event_date"))).isEqualTo("2026-06-06");
        assertThat(event.get("reason")).isEqualTo("个人原因");

        var log = latestChangeLog(created.get("employeeNo").asText());
        assertThat(log.get("admin_username").asText()).isEqualTo(USERNAME);
        assertThat(log.get("request_id").asText()).isEqualTo(rid);
        assertThat(objectMapper.readTree(log.get("before_json").asText()).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(objectMapper.readTree(log.get("after_json").asText()).get("status").asText()).isEqualTo("LEFT");
    }

    @Test
    void secondLeaveReturnsStateNotEditable() throws Exception {
        var id = createAndId("孙八-员工API", "2026-07-07", List.of("MAKING"));
        leave(id, "不干了", "2026-08-08").andExpect(status().isOk());
        leave(id, "再说一次", "2026-08-09")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_NOT_EDITABLE"));
    }

    @Test
    void rehireReactivatesAndKeepsFirstHireDate() throws Exception {
        var created = create("周九-员工API", "2026-01-02", List.of("MAKING"));
        var id = created.get("id").asLong();
        leave(id, "休息一段", "2026-02-02").andExpect(status().isOk());

        mockMvc.perform(post(BASE + "/" + id + "/rehire")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content(objectMapper.writeValueAsBytes(Map.of("date", "2026-03-03"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.firstHireDate").value("2026-01-02"));

        var rehire = jdbcTemplate.queryForMap(
                "SELECT event_date FROM employee_employment_events WHERE employee_id = ? AND event_type = 'REHIRE'",
                id);
        assertThat(String.valueOf(rehire.get("event_date"))).isEqualTo("2026-03-03");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT first_hire_date FROM employees WHERE id = ?", String.class, id)).isEqualTo("2026-01-02");
        assertThat(latestChangeLog(created.get("employeeNo").asText()).get("admin_username").asText())
                .isEqualTo(USERNAME);

        // 在职再离职/再入职 → 409；不存在的员工 → 404
        mockMvc.perform(post(BASE + "/" + id + "/rehire")
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content(objectMapper.writeValueAsBytes(Map.of("date", "2026-04-04"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_NOT_EDITABLE"));
        mockMvc.perform(get(BASE + "/999999999").cookie(sessionCookie))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void invalidWorkTypeReturnsValidationInvalidWithFieldErrors() throws Exception {
        mockMvc.perform(post(BASE)
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "name", "非法工种", "firstHireDate", "2026-01-01",
                                "workTypes", List.of("电焊")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("workTypes"))
                .andExpect(jsonPath("$.fieldErrors[0].message").isString());

        mockMvc.perform(post(BASE)
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key())
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "firstHireDate", "2026-01-01", "workTypes", List.of("MAKING")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
    }

    @Test
    void missingIdempotencyKeyReturnsValidationInvalid() throws Exception {
        mockMvc.perform(post(BASE)
                        .cookie(sessionCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "name", "无幂等键", "firstHireDate", "2026-01-01",
                                "workTypes", List.of("MAKING")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("Idempotency-Key"));
    }
}
package com.yumi;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 9.5（代码侧）：指标端点（metrics/prometheus）可达且受保护、5xx 计数、文件存储可写性健康组件。
 * 告警规则、慢查询与备份失败告警属运维侧，见 tasks.md 9.5 的未做项说明。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ObservabilityApiTest {

    private static final String USERNAME = "observability-test-admin";
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
                INSERT INTO admin_accounts (username, password_hash, status, version, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, USERNAME, passwordEncoder.encode(PASSWORD));
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
    }

    @Test
    void metricsAndPrometheusRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/actuator/metrics")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/actuator/prometheus")).andExpect(status().isUnauthorized());
    }

    @Test
    void exposesMetricsForAuthenticatedOperator() throws Exception {
        var metrics = mockMvc.perform(get("/api/actuator/metrics").cookie(sessionCookie)).andReturn();
        assertThat(metrics.getResponse().getStatus())
                .as("metrics 端点状态，响应体=" + metrics.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(metrics.getResponse().getContentAsString()).contains("\"names\"");
        assertThat(metrics.getResponse().getContentAsString()).contains("disk.free");
        // Prometheus 抓取端点（`/api/actuator/prometheus`）在**打包产物与运行中应用**验证：
        // 实测返回 `# HELP jvm_memory_used_bytes`（见 tasks.md 9.5 证据）；MockMvc 上下文只暴露
        // health/info/metrics 三个端点，故此处不对抓取端点做断言，避免用测试环境差异冒充结论。
    }

    @Test
    void exposesFileStorageWritabilityMetric() throws Exception {
        var prometheus = mockMvc.perform(get("/api/actuator/prometheus").cookie(sessionCookie)).andReturn();
        // MockMvc 上下文可能未注册 prometheus 抓取端点（见另一用例说明）：此时用 metrics 端点核对指标存在
        if (prometheus.getResponse().getStatus() == 200) {
            assertThat(prometheus.getResponse().getContentAsString())
                    .contains("yumi_file_storage_writable");
        } else {
            var metrics = mockMvc.perform(get("/api/actuator/metrics/yumi.file.storage.writable")
                            .cookie(sessionCookie))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(metrics.getResponse().getContentAsString())
                    .contains("yumi.file.storage.writable")
                    .contains("1.0");
        }
    }

    @Test
    void healthIncludesFileStorageWritability() throws Exception {
        mockMvc.perform(get("/api/actuator/health").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.components.fileStorage.status").value("UP"))
                .andExpect(jsonPath("$.data.components.fileStorage.details.directory").isNotEmpty());
    }

    @Test
    void countsServerErrorsWithRouteTemplateLabel() throws Exception {
        var registry = new SimpleMeterRegistry();
        var filter = new com.yumi.shared.observability.HttpServerErrorMetricsFilter(registry);
        var request = new MockHttpServletRequest("POST", "/api/orders/123/confirm");
        request.setAttribute("org.springframework.web.servlet.HandlerMapping.bestMatchingPattern",
                "/api/orders/{id}/confirm");

        var failing = new MockHttpServletResponse();
        failing.setStatus(500);
        filter.doFilter(request, failing, (req, res) -> ((MockHttpServletResponse) res).setStatus(500));
        // 4xx 不计入 5xx 指标
        var badRequest = new MockHttpServletResponse();
        badRequest.setStatus(409);
        filter.doFilter(request, badRequest, (req, res) -> ((MockHttpServletResponse) res).setStatus(409));

        var counter = registry.find(com.yumi.shared.observability.HttpServerErrorMetricsFilter.METRIC)
                .tag("status", "500").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
        assertThat(registry.find(com.yumi.shared.observability.HttpServerErrorMetricsFilter.METRIC)
                .tag("status", "409").counter()).isNull();
        assertThat(registry.find(com.yumi.shared.observability.HttpServerErrorMetricsFilter.METRIC)
                .tag("uri", "/api/orders/{id}/confirm").counter()).isNotNull();
    }
}

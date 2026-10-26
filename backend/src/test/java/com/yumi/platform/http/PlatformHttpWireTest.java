package com.yumi.platform.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.http.HttpHeaders;
import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.Quantity;
import com.yumi.platform.identity.infrastructure.SessionAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台 HTTP 线上行为：十进制字符串往返、请求上下文与错误映射。
 *
 * <p>本测试用最薄的探测控制器验证映射契约，不代表任何业务命令已实现；真实认证与持久幂等在
 * 2.11 的 {@code PlatformHttpContractIT} 验证。
 */
@WebMvcTest(
        controllers = PlatformHttpWireTest.ProbeController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = SessionAuthenticationFilter.class))
@Import({ApiExceptionHandler.class, HttpRequestContextResolver.class, ValueJacksonConfiguration.class,
        PlatformHttpWireTest.ProbeController.class})
class PlatformHttpWireTest {

    @Autowired
    private MockMvc mockMvc;

    /** 金额 + 数量往返载体。 */
    public record ProbeRequest(CostAmount amount, Quantity quantity) {
    }

    public record ProbeResponse(String amount, String currency, String quantity) {
    }

    @Test
    @DisplayName("金额以 {amount,currency} 字符串往返，且响应头回传请求编号")
    void moneyRoundTripsAsDecimalString() throws Exception {
        mockMvc.perform(post("/probe/echo")
                        .header(HttpHeaders.IDEMPOTENCY_KEY, "idem-1")
                        .header(HttpHeaders.REQUEST_ID, "req-test-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":{\"amount\":\"12.5000\",\"currency\":\"CNY\"},"
                                + "\"quantity\":\"7\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.REQUEST_ID, "req-test-1"))
                .andExpect(jsonPath("$.amount").value("12.5000"))
                .andExpect(jsonPath("$.currency").value("CNY"))
                .andExpect(jsonPath("$.quantity").value("7"));
    }

    @Test
    @DisplayName("JSON 浮点金额被拒绝为 VALIDATION，不产生业务结果")
    void floatingPointMoneyIsRejected() throws Exception {
        mockMvc.perform(post("/probe/echo")
                        .header(HttpHeaders.IDEMPOTENCY_KEY, "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":{\"amount\":12.5,\"currency\":\"CNY\"},\"quantity\":\"7\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("VALIDATION"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("正式命令缺少幂等键时被拒绝")
    void commandWithoutIdempotencyKeyIsRejected() throws Exception {
        mockMvc.perform(post("/probe/command")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("VALIDATION"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("幂等")));
    }

    @Test
    @DisplayName("查询不要求幂等键，且请求编号缺失时由服务端生成并回传")
    void queryDoesNotRequireIdempotencyKey() throws Exception {
        mockMvc.perform(get("/probe/query"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getHeader(HttpHeaders.REQUEST_ID))
                        .startsWith("req-"));
    }

    @Test
    @DisplayName("业务异常映射为稳定错误类型与错误码")
    void businessExceptionIsMapped() throws Exception {
        mockMvc.perform(get("/probe/reject"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("BUSINESS_REJECTION"))
                .andExpect(jsonPath("$.code").value("OBLIGATION_UNFULFILLED"))
                .andExpect(jsonPath("$.message").value("仍有未完成义务，不能结案"));
    }

    @Test
    @DisplayName("缺少必需查询参数返回 VALIDATION 而不是 SYSTEM_FAILURE")
    void missingRequestParameterIsValidation() throws Exception {
        mockMvc.perform(get("/probe/requires-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("VALIDATION"))
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
    }

    @Test
    @DisplayName("查询参数类型不匹配返回 VALIDATION 而不是 SYSTEM_FAILURE")
    void malformedRequestParameterIsValidation() throws Exception {
        mockMvc.perform(get("/probe/requires-date").param("workDate", "not-a-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("VALIDATION"))
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("未预期异常返回 SYSTEM_FAILURE 且不泄露堆栈与内部类名")
    void unexpectedExceptionHidesInternals() throws Exception {
        String body = mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("SYSTEM_FAILURE"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain("NullPointerException")
                .doesNotContain("ProbeController")
                .doesNotContain("\tat ");
    }

    /**
     * 仅测试用探测控制器：验证映射层，不含业务规则。
     */
    @RestController
    @RequestMapping("/probe")
    public static class ProbeController {

        private final HttpRequestContextResolver contextResolver;

        public ProbeController(HttpRequestContextResolver contextResolver) {
            this.contextResolver = contextResolver;
        }

        @PostMapping("/echo")
        public ProbeResponse echo(@RequestBody ProbeRequest request, HttpServletRequest servletRequest) {
            contextResolver.resolveCommand(servletRequest);
            return new ProbeResponse(
                    request.amount().value().toPlainString(),
                    request.amount().currency().value(),
                    Long.toString(request.quantity().value()));
        }

        @PostMapping("/command")
        public void command(HttpServletRequest servletRequest) {
            contextResolver.resolveCommand(servletRequest);
        }

        @GetMapping("/query")
        public void query(HttpServletRequest servletRequest) {
            contextResolver.resolveQuery(servletRequest);
        }

        @GetMapping("/reject")
        public void reject() {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "OBLIGATION_UNFULFILLED", "仍有未完成义务，不能结案");
        }

        @GetMapping("/boom")
        public void boom() {
            throw new IllegalStateException("internal detail must not leak");
        }

        @GetMapping("/requires-param")
        public void requiresParam(@org.springframework.web.bind.annotation.RequestParam String workDate) {
        }

        @GetMapping("/requires-date")
        public void requiresDate(
                @org.springframework.web.bind.annotation.RequestParam java.time.LocalDate workDate) {
        }
    }
}

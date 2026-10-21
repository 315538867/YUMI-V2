package com.yumi.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.api.http.ApiError;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.http.HttpHeaders;
import com.yumi.platform.api.http.RequestContext;
import com.yumi.platform.api.http.json.YumiValueModule;
import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.DurationSeconds;
import com.yumi.platform.api.value.Quantity;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.platform.api.value.WeightGram;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * HTTP 线上契约：十进制字符串、稳定错误序列化与请求上下文/幂等头。
 *
 * <p>规则来源：`docs/一期/04-公共业务与技术规则.md` 第 2、8 节，`05-跨模块接口与事务规则.md`
 * 第 9、11 节。金额不得以二进制浮点传输；错误必须保留稳定类型与请求编号并区分处理；
 * 正式命令必须携带幂等键。
 *
 * <p>本测试只验证映射契约，使用内存中的序列化器；真实认证与持久幂等在 2.11 验证。
 */
class HttpWireContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new YumiValueModule());

    @Nested
    @DisplayName("十进制字符串契约")
    class DecimalStringContract {

        @Test
        @DisplayName("成本金额序列化为 {amount: 十进制字符串, currency}，不是浮点数")
        void costAmountSerializesAsStringWithCurrency() throws Exception {
            CostAmount amount = CostAmount.of(new BigDecimal("12.5"), CurrencyCode.CNY);

            JsonNode node = MAPPER.valueToTree(amount);

            assertThat(node.isObject()).as("金额必须携带币种，不能序列化为裸数字").isTrue();
            assertThat(node.get("amount").isTextual())
                    .as("金额值必须是 JSON 字符串，不能是浮点数")
                    .isTrue();
            assertThat(node.get("amount").asText()).isEqualTo("12.5000");
            assertThat(node.get("currency").asText()).isEqualTo("CNY");
        }

        @Test
        @DisplayName("结算金额保留 2 位小数")
        void settlementMoneyKeepsTwoDecimals() {
            SettlementMoney money = SettlementMoney.of(new BigDecimal("1000"), CurrencyCode.CNY);
            JsonNode node = MAPPER.valueToTree(money);
            assertThat(node.get("amount").asText()).isEqualTo("1000.00");
            assertThat(node.get("currency").asText()).isEqualTo("CNY");
        }

        @Test
        @DisplayName("重量序列化为十进制字符串并保留 4 位小数")
        void weightSerializesAsString() {
            WeightGram weight = WeightGram.ofGrams(new BigDecimal("105"));
            JsonNode node = MAPPER.valueToTree(weight);
            assertThat(node.isTextual()).isTrue();
            assertThat(node.asText()).isEqualTo("105.0000");
        }

        @Test
        @DisplayName("数量与秒以字符串传输，避免超过 JS 安全整数范围")
        void largeIntegersSerializeAsStrings() {
            assertThat(MAPPER.valueToTree(Quantity.of(9_007_199_254_740_993L)).asText())
                    .isEqualTo("9007199254740993");
            assertThat(MAPPER.valueToTree(DurationSeconds.of(93L)).asText()).isEqualTo("93");
        }

        @Test
        @DisplayName("反序列化接受字符串对象，拒绝 JSON 浮点数与科学计数法")
        void deserializationRejectsNonDecimalForms() throws Exception {
            CostAmount parsed = MAPPER.readValue(
                    "{\"amount\":\"12.5000\",\"currency\":\"CNY\"}", CostAmount.class);
            assertThat(parsed.value()).isEqualByComparingTo("12.5000");

            assertThatThrownBy(() -> MAPPER.readValue(
                    "{\"amount\":12.5,\"currency\":\"CNY\"}", CostAmount.class))
                    .as("JSON 浮点数会丢失精度，必须拒绝")
                    .isInstanceOf(Exception.class);

            assertThatThrownBy(() -> MAPPER.readValue(
                    "{\"amount\":\"1e3\",\"currency\":\"CNY\"}", CostAmount.class))
                    .as("科学计数法不是契约要求的十进制字符串")
                    .isInstanceOf(Exception.class);

            assertThatThrownBy(() -> MAPPER.readValue("\"12.5000\"", CostAmount.class))
                    .as("金额必须同时携带币种")
                    .isInstanceOf(Exception.class);

            assertThatThrownBy(() -> MAPPER.readValue(
                    "{\"amount\":\"12.5\"}", CostAmount.class))
                    .as("缺少币种必须拒绝")
                    .isInstanceOf(Exception.class);
        }
    }

    @Nested
    @DisplayName("错误序列化契约")
    class ErrorSerializationContract {

        @Test
        @DisplayName("错误响应携带稳定类型、错误码、请求编号且不含堆栈")
        void errorResponseIsStableAndSafe() throws Exception {
            ApiError error = ApiError.of(
                    ApiErrorType.CONCURRENT_CONFLICT,
                    "ROW_VERSION_CONFLICT",
                    "数据已被其他操作修改，请刷新后重试",
                    "req-123");

            String json = MAPPER.writeValueAsString(error);
            JsonNode node = MAPPER.readTree(json);

            assertThat(node.get("type").asText()).isEqualTo("CONCURRENT_CONFLICT");
            assertThat(node.get("code").asText()).isEqualTo("ROW_VERSION_CONFLICT");
            assertThat(node.get("requestId").asText()).isEqualTo("req-123");
            assertThat(node.has("stackTrace")).isFalse();
            assertThat(node.has("cause")).isFalse();
            assertThat(json).doesNotContain("Exception").doesNotContain("\tat ");
        }

        @Test
        @DisplayName("六类稳定错误类型与 HTTP 状态一一对应")
        void errorTypesMapToHttpStatus() {
            assertThat(ApiErrorType.VALIDATION.httpStatus()).isEqualTo(400);
            assertThat(ApiErrorType.BUSINESS_REJECTION.httpStatus()).isEqualTo(422);
            assertThat(ApiErrorType.NOT_FOUND.httpStatus()).isEqualTo(404);
            assertThat(ApiErrorType.FORBIDDEN.httpStatus()).isEqualTo(403);
            assertThat(ApiErrorType.CONCURRENT_CONFLICT.httpStatus()).isEqualTo(409);
            assertThat(ApiErrorType.IDEMPOTENCY_CONFLICT.httpStatus()).isEqualTo(409);
            assertThat(ApiErrorType.SYSTEM_FAILURE.httpStatus()).isEqualTo(500);
            assertThat(ApiErrorType.values()).hasSize(7);
        }

        @Test
        @DisplayName("业务异常保留错误类型与错误码，供适配层映射")
        void apiExceptionCarriesTypeAndCode() {
            ApiException exception = new ApiException(
                    ApiErrorType.IDEMPOTENCY_CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "相同幂等键使用了不同输入");

            assertThat(exception.type()).isEqualTo(ApiErrorType.IDEMPOTENCY_CONFLICT);
            assertThat(exception.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
            assertThat(exception.getMessage()).isEqualTo("相同幂等键使用了不同输入");
        }

        @Test
        @DisplayName("字段校验错误逐项列出字段名与原因")
        void fieldErrorsAreExplicit() {
            ApiError error = ApiError.validation(
                    "请求参数不合法",
                    "req-456",
                    List.of(new ApiError.FieldIssue("quantity", "不能为负"),
                            new ApiError.FieldIssue("amount", "精度超出 4 位")));

            assertThat(error.type()).isEqualTo(ApiErrorType.VALIDATION);
            assertThat(error.fieldIssues()).hasSize(2);
            assertThat(error.fieldIssues().getFirst().field()).isEqualTo("quantity");
        }

        @Test
        @DisplayName("错误默认可携带中文面向用户消息，但不含内部异常类名")
        void defaultRejectionMessageHasNoInternalDetail() {
            ApiError error = ApiError.of(
                    ApiErrorType.BUSINESS_REJECTION, "OBLIGATION_UNFULFILLED",
                    "仍有未完成义务，不能结案", "req-789");

            assertThat(error.message()).doesNotContain("Service").doesNotContain("Mapper");
        }
    }

    @Nested
    @DisplayName("请求上下文与幂等头")
    class RequestContextContract {

        @Test
        @DisplayName("幂等与请求编号使用固定请求头")
        void headerNamesAreStable() {
            assertThat(HttpHeaders.IDEMPOTENCY_KEY).isEqualTo("Idempotency-Key");
            assertThat(HttpHeaders.REQUEST_ID).isEqualTo("X-Request-Id");
        }

        @Test
        @DisplayName("正式命令缺少幂等键时明确拒绝")
        void idempotencyKeyIsRequiredForCommands() {
            assertThatThrownBy(() -> RequestContext.forCommand(null, "req-1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("幂等");
            assertThatThrownBy(() -> RequestContext.forCommand("  ", "req-1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("幂等");
            assertThatThrownBy(() -> RequestContext.forCommand("k".repeat(129), "req-1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("128");
        }

        @Test
        @DisplayName("查询不需要幂等键，但仍保留请求编号")
        void queryNeedsRequestIdOnly() {
            RequestContext context = RequestContext.forQuery("req-2");
            assertThat(context.requestId()).isEqualTo("req-2");
            assertThat(context.idempotencyKeyOptional()).isEmpty();
            assertThat(context.idempotencyKey()).isNull();
            assertThat(context.isCommand()).isFalse();
        }

        @Test
        @DisplayName("请求编号不能为空，用于审计与问题定位")
        void requestIdIsRequired() {
            assertThatThrownBy(() -> RequestContext.forCommand("idem-1", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("请求编号");
            assertThat(RequestContext.forCommand("idem-1", "req-3").isCommand()).isTrue();
        }

        @Test
        @DisplayName("业务日期与时区显式声明，不依赖服务器默认时区")
        void requestContextDeclaresBusinessZone() {
            RequestContext context = RequestContext.forQuery("req-4");
            assertThat(context.businessZone()).isEqualTo("Asia/Shanghai");
            assertThatThrownBy(() -> RequestContext.forQuery("req-5", "Mars/Olympus"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("时区");
        }
    }
}

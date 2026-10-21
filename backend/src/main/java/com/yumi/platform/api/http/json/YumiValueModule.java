package com.yumi.platform.api.http.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.yumi.platform.api.value.ContentFingerprint;
import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.DurationMinutes;
import com.yumi.platform.api.value.DurationSeconds;
import com.yumi.platform.api.value.EfficiencyFactor;
import com.yumi.platform.api.value.LossRate;
import com.yumi.platform.api.value.MoneyAmount;
import com.yumi.platform.api.value.Quantity;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.platform.api.value.SignedCostAmount;
import com.yumi.platform.api.value.SignedQuantity;
import com.yumi.platform.api.value.SignedSettlementAmount;
import com.yumi.platform.api.value.StableBusinessId;
import com.yumi.platform.api.value.WeightGram;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.function.Function;
import java.util.function.LongFunction;

/**
 * 值对象的 HTTP 线上契约。
 *
 * <ul>
 *   <li>金额：{@code {"amount":"12.5000","currency":"CNY"}}，金额为十进制字符串并携带币种；</li>
 *   <li>重量、损耗率、效率因子：十进制 JSON 字符串，保留各自精度；</li>
 *   <li>数量、秒、分钟：JSON 字符串，避免超过 JS 安全整数范围后静默丢精度；</li>
 *   <li>指纹、币种、稳定标识：JSON 字符串；</li>
 *   <li>所有反序列化都拒绝 JSON 数值与科学计数法。</li>
 * </ul>
 */
public class YumiValueModule extends SimpleModule {

    public YumiValueModule() {
        super("yumi-value-module");

        addSerializer(CostAmount.class, moneySerializer(CostAmount::value, CostAmount::currency));
        addDeserializer(CostAmount.class,
                moneyDeserializer(4, (value, currency) -> CostAmount.of(value, currency)));
        addSerializer(SignedCostAmount.class, moneySerializer(SignedCostAmount::value, SignedCostAmount::currency));
        addDeserializer(SignedCostAmount.class,
                moneyDeserializer(4, (value, currency) -> SignedCostAmount.of(value, currency)));
        addSerializer(SettlementMoney.class, moneySerializer(SettlementMoney::value, SettlementMoney::currency));
        addDeserializer(SettlementMoney.class,
                moneyDeserializer(2, (value, currency) -> SettlementMoney.of(value, currency)));
        addSerializer(SignedSettlementAmount.class,
                moneySerializer(SignedSettlementAmount::value, SignedSettlementAmount::currency));
        addDeserializer(SignedSettlementAmount.class,
                moneyDeserializer(2, (value, currency) -> SignedSettlementAmount.of(value, currency)));

        addSerializer(WeightGram.class, decimalSerializer(WeightGram::value));
        addDeserializer(WeightGram.class, decimalDeserializer("重量", WeightGram::ofGrams));
        addSerializer(LossRate.class, decimalSerializer(LossRate::value));
        addDeserializer(LossRate.class, decimalDeserializer("损耗率", LossRate::of));
        addSerializer(EfficiencyFactor.class, decimalSerializer(EfficiencyFactor::value));
        addDeserializer(EfficiencyFactor.class, decimalDeserializer("效率因子", EfficiencyFactor::of));

        addSerializer(Quantity.class, integerSerializer(Quantity::value));
        addDeserializer(Quantity.class, integerDeserializer("数量", Quantity::of));
        addSerializer(SignedQuantity.class, integerSerializer(SignedQuantity::value));
        addDeserializer(SignedQuantity.class, integerDeserializer("数量差额", SignedQuantity::of));
        addSerializer(DurationSeconds.class, integerSerializer(DurationSeconds::value));
        addDeserializer(DurationSeconds.class, integerDeserializer("秒数", DurationSeconds::of));
        addSerializer(DurationMinutes.class, integerSerializer(minutes -> (long) minutes.value()));
        addDeserializer(DurationMinutes.class,
                integerDeserializer("分钟数", value -> DurationMinutes.of(Math.toIntExact(value))));

        addSerializer(CurrencyCode.class, textSerializer(CurrencyCode::value));
        addDeserializer(CurrencyCode.class, textDeserializer("币种", CurrencyCode::of));
        addSerializer(ContentFingerprint.class, textSerializer(ContentFingerprint::value));
        addDeserializer(ContentFingerprint.class, textDeserializer("内容指纹", ContentFingerprint::of));
        addSerializer(StableBusinessId.class, textSerializer(StableBusinessId::value));
        addDeserializer(StableBusinessId.class, textDeserializer("稳定业务标识", StableBusinessId::of));
    }

    /** 金额工厂：从已校验精度的金额与币种构造具体类型。 */
    private interface MoneyFactory<T> {
        T create(BigDecimal value, CurrencyCode currency);
    }

    private static <T extends MoneyAmount> JsonSerializer<T> moneySerializer(
            Function<T, BigDecimal> value, Function<T, CurrencyCode> currency) {
        return new JsonSerializer<>() {
            @Override
            public void serialize(T amount, JsonGenerator generator, SerializerProvider provider)
                    throws IOException {
                generator.writeStartObject();
                generator.writeStringField("amount", value.apply(amount).toPlainString());
                generator.writeStringField("currency", currency.apply(amount).value());
                generator.writeEndObject();
            }
        };
    }

    private static <T> JsonDeserializer<T> moneyDeserializer(int scale, MoneyFactory<T> factory) {
        return new JsonDeserializer<>() {
            @Override
            public T deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                JsonNodeReader reader = JsonNodeReader.of(parser);
                String amount = reader.requireTextField("amount");
                String currency = reader.requireTextField("currency");
                BigDecimal value = DecimalText.parse(amount, "金额")
                        .setScale(scale, java.math.RoundingMode.HALF_UP);
                return factory.create(value, CurrencyCode.of(currency));
            }
        };
    }

    private static <T> JsonSerializer<T> decimalSerializer(Function<T, BigDecimal> value) {
        return new JsonSerializer<>() {
            @Override
            public void serialize(T object, JsonGenerator generator, SerializerProvider provider)
                    throws IOException {
                generator.writeString(value.apply(object).toPlainString());
            }
        };
    }

    private static <T> JsonDeserializer<T> decimalDeserializer(
            String field, Function<BigDecimal, T> factory) {
        return new JsonDeserializer<>() {
            @Override
            public T deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                String raw = requireStringToken(parser, field);
                return factory.apply(DecimalText.parse(raw, field));
            }
        };
    }

    private static <T> JsonSerializer<T> integerSerializer(java.util.function.ToLongFunction<T> value) {
        return new JsonSerializer<>() {
            @Override
            public void serialize(T object, JsonGenerator generator, SerializerProvider provider)
                    throws IOException {
                generator.writeString(Long.toString(value.applyAsLong(object)));
            }
        };
    }

    private static <T> JsonDeserializer<T> integerDeserializer(String field, LongFunction<T> factory) {
        return new JsonDeserializer<>() {
            @Override
            public T deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                String raw = requireStringToken(parser, field);
                if (!raw.matches("-?(0|[1-9][0-9]*)")) {
                    throw new IllegalArgumentException(field + " 必须是整数字符串：" + raw);
                }
                return factory.apply(Long.parseLong(raw));
            }
        };
    }

    private static <T> JsonSerializer<T> textSerializer(Function<T, String> value) {
        return new JsonSerializer<>() {
            @Override
            public void serialize(T object, JsonGenerator generator, SerializerProvider provider)
                    throws IOException {
                generator.writeString(value.apply(object));
            }
        };
    }

    private static <T> JsonDeserializer<T> textDeserializer(String field, Function<String, T> factory) {
        return new JsonDeserializer<>() {
            @Override
            public T deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                return factory.apply(requireStringToken(parser, field));
            }
        };
    }

    /**
     * 只接受 JSON 字符串 token：JSON 数值会先在 Jackson 中变成 double/long，精度已不可信。
     */
    private static String requireStringToken(JsonParser parser, String field) throws IOException {
        if (!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_STRING)) {
            throw new IllegalArgumentException(field + " 必须以 JSON 字符串传输，不能是数字或对象");
        }
        return parser.getText();
    }

    /** 读取金额对象字段，同时拒绝非对象与字段类型错误。 */
    private record JsonNodeReader(com.fasterxml.jackson.databind.JsonNode node) {

        static JsonNodeReader of(JsonParser parser) throws IOException {
            com.fasterxml.jackson.databind.JsonNode node =
                    parser.getCodec().readTree(parser);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("金额必须以对象形式传输：{\"amount\":\"…\",\"currency\":\"CNY\"}");
            }
            return new JsonNodeReader(node);
        }

        String requireTextField(String field) {
            com.fasterxml.jackson.databind.JsonNode value = node.get(field);
            if (value == null || !value.isTextual()) {
                throw new IllegalArgumentException("金额字段 " + field + " 必须是 JSON 字符串");
            }
            return value.asText();
        }
    }
}

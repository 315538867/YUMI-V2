package com.yumi.platform.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.api.http.json.YumiValueModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * 注册值对象线上契约。
 *
 * <p>金额、重量、比例、数量与时长统一以十进制/整数字符串传输，携带币种；解析时拒绝 JSON 数值
 * 与科学计数法。所有模块的 HTTP 适配层共享同一序列化配置，避免各页面对金额格式各自约定。
 */
@Configuration
public class ValueJacksonConfiguration {

    @Bean
    public YumiValueModule yumiValueModule() {
        return new YumiValueModule();
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer yumiValueModuleCustomizer(YumiValueModule module) {
        return (Jackson2ObjectMapperBuilder builder) -> builder.modulesToInstall(module);
    }

    /** 供非 Spring 上下文的测试与工具复用同一配置。 */
    public static ObjectMapper standardObjectMapper() {
        return new ObjectMapper().registerModule(new YumiValueModule());
    }
}

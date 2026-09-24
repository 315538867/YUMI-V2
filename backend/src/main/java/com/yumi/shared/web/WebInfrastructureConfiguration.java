package com.yumi.shared.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.shared.idempotency.IdempotencyFilter;
import com.yumi.shared.idempotency.IdempotencyRecordStore;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class WebInfrastructureConfiguration {

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        var registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        return registration;
    }

    @Bean
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(IdempotencyRecordStore store,
                                                                ObjectMapper objectMapper) {
        var registration = new FilterRegistrationBean<>(new IdempotencyFilter(store, objectMapper));
        registration.setOrder(0);
        registration.addUrlPatterns("/api/*");
        return registration;
    }
}

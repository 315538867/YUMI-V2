package com.yumi.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WriteAuditConfiguration {

    @Bean
    FilterRegistrationBean<WriteAuditFilter> writeAuditFilter(WriteAuditRepository repository,
                                                              ObjectMapper objectMapper) {
        var registration = new FilterRegistrationBean<>(new WriteAuditFilter(repository, objectMapper));
        registration.setOrder(-50);
        registration.addUrlPatterns("/api/*");
        return registration;
    }
}

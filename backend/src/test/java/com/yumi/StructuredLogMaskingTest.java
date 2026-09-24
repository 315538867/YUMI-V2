package com.yumi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 1.10 结构化日志与脱敏：控制台输出为结构化 JSON；按字段名与消息内模式脱敏，
 * 密码、令牌、Cookie 值与 MDC 凭据字段不以明文出现。
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest
class StructuredLogMaskingTest {

    private static final Logger probe = LoggerFactory.getLogger("yumi.log.probe");

    @Test
    void structuredJsonOutputWithMaskedSecrets(CapturedOutput output) {
        probe.info("login attempt password=TopSecret123 cookie=YUMI_SESSION=Abc.Def123 authorization: Bearer xyz.token.789");
        MDC.put("password", "MdcSecret789");
        MDC.put("requestId", "mask-req-1");
        try {
            probe.info("session opened");
        } finally {
            MDC.remove("password");
            MDC.remove("requestId");
        }

        var text = output.toString();
        // 结构化：探针日志行为 JSON（含 level 与 logger 字段）
        assertThat(text).contains("\"level\"");
        assertThat(text).contains("yumi.log.probe");
        // 消息内容脱敏
        assertThat(text).doesNotContain("TopSecret123");
        assertThat(text).doesNotContain("Abc.Def123");
        assertThat(text).doesNotContain("xyz.token.789");
        assertThat(text).contains("****");
        // 按字段名脱敏 MDC 凭据字段，同时保留非敏感 requestId
        assertThat(text).doesNotContain("MdcSecret789");
        assertThat(text).contains("mask-req-1");
    }
}

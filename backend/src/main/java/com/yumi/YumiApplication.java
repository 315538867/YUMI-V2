package com.yumi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 一期业务系统模块化单体入口。
 *
 * <p>九个顶层模块均位于 {@code com.yumi} 下：platform、calculation、customer、product、
 * workforce、production、orders、finance、reporting。模块之间只能通过公开 API 与
 * 独立来源 Feed 协作，不能跨模块访问内部实现。
 */
@SpringBootApplication
public class YumiApplication {

    public static void main(String[] args) {
        SpringApplication.run(YumiApplication.class, args);
    }
}

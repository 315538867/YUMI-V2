package com.yumi;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 工程骨架的最小启动契约：入口类存在、可执行且声明为 Spring Boot 应用。
 *
 * <p>本测试不启动 Spring 上下文，因此不连接任何数据库；真实容器迁移 smoke 在工程骨架任务中
 * 由独立 MySQL 集成测试完成。
 */
class YumiApplicationSmokeTest {

    @Test
    @DisplayName("入口类声明 SpringBootApplication 且提供可执行的 main 方法")
    void applicationEntryPointIsRunnable() throws Exception {
        assertThat(YumiApplication.class.isAnnotationPresent(SpringBootApplication.class)).isTrue();

        Method main = YumiApplication.class.getMethod("main", String[].class);
        assertThat(Modifier.isStatic(main.getModifiers())).isTrue();
        assertThat(Modifier.isPublic(main.getModifiers())).isTrue();
        assertThat(main.getReturnType()).isEqualTo(void.class);
    }

    @Test
    @DisplayName("入口类位于 com.yumi 根包，九个模块可作为其子包")
    void applicationPackageIsRoot() {
        assertThat(YumiApplication.class.getPackageName()).isEqualTo("com.yumi");
    }
}

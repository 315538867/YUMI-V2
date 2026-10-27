package com.yumi.platform.identity.bootstrap;

import com.yumi.platform.identity.application.IdentityApplicationService;
import com.yumi.platform.identity.application.port.AccountRecord;
import com.yumi.platform.identity.application.port.AccountRepository;
import com.yumi.platform.identity.application.port.PasswordHasher;
import com.yumi.platform.identity.application.port.SessionRepository;
import java.io.Console;
import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.SpringApplication;

/**
 * 运维口令重置（CLI，不注册 HTTP 入口）。
 *
 * <p>用于初始管理员口令泄露或需要轮换时显式执行；重置后立即吊销该账号全部会话，
 * 不打印口令，也不修改角色或账号状态。
 */
public final class ResetAdministratorPasswordCommand {

    public static final String LOGIN_ENV = "YUMI_ADMIN_LOGIN";
    public static final String PASSWORD_ENV = "YUMI_ADMIN_NEW_PASSWORD";

    private ResetAdministratorPasswordCommand() {
    }

    public static void main(String[] args) {
        String loginName = System.getenv(LOGIN_ENV);
        if (loginName == null || loginName.isBlank()) {
            System.err.println("请设置 " + LOGIN_ENV);
            System.exit(2);
            return;
        }
        String password = System.getenv(PASSWORD_ENV);
        if (password == null || password.isBlank()) {
            Console console = System.console();
            if (console == null) {
                System.err.println("无法读取口令：请设置 " + PASSWORD_ENV + " 或使用交互终端");
                System.exit(2);
                return;
            }
            char[] entered = console.readPassword("请输入新口令（不回显）：");
            password = entered == null ? null : new String(entered);
            if (entered != null) {
                java.util.Arrays.fill(entered, '\0');
            }
        }
        if (password == null || password.length() < IdentityApplicationService.MIN_PASSWORD_LENGTH) {
            System.err.println("口令长度不能少于 " + IdentityApplicationService.MIN_PASSWORD_LENGTH + " 位");
            System.exit(2);
            return;
        }

        var context = SpringApplication.run(
                com.yumi.YumiApplication.class, "--spring.main.web-application-type=none");
        try {
            AccountRepository accounts = context.getBean(AccountRepository.class);
            PasswordHasher hasher = context.getBean(PasswordHasher.class);
            SessionRepository sessions = context.getBean(SessionRepository.class);
            Clock clock = context.getBean(Clock.class);
            AccountRecord account = accounts.findByLoginName(loginName.strip())
                    .orElseThrow(() -> new IllegalStateException("账号不存在：" + loginName));
            Instant now = clock.instant();
            boolean updated = accounts.updatePasswordHash(
                    account.accountBusinessId(), hasher.hash(password), account.rowVersion(), now,
                    "ops-password-reset");
            if (!updated) {
                throw new IllegalStateException("账号已被并发修改，请重试");
            }
            int revoked = sessions.revokeAllActiveOfAccount(account.accountBusinessId(), now);
            System.out.println("口令已重置：" + account.loginName() + "；已吊销会话 " + revoked + " 个");
        } finally {
            context.close();
            password = null;
        }
    }
}

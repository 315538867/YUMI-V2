package com.yumi.platform.identity.bootstrap;

import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.identity.AccountRef;
import com.yumi.platform.api.value.StableBusinessId;
import com.yumi.platform.authorization.application.port.RoleRecord;
import com.yumi.platform.authorization.application.port.RoleRepository;
import com.yumi.platform.identity.application.IdentityApplicationService;
import java.io.Console;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 首次管理员一次性引导（运维 CLI，不是网页注册）。
 *
 * <p>规则来源：`docs/一期/06-平台公共能力模块.md` §5、§11 与 `specs/platform-security/spec.md`
 * PL-01 的“首次管理员引导”场景：
 * <ul>
 *   <li>只在系统尚无账号时成功；系统非空时拒绝，不重置已有权限；</li>
 *   <li>空系统守卫在事务内检查，并发引导只有一个成功；</li>
 *   <li>初始口令从环境或标准输入读取，不写默认口令、不记录、不打印；</li>
 *   <li>原子创建管理员账号与内置管理员角色，不开放匿名网页注册。</li>
 * </ul>
 *
 * <p>本类不注册 HTTP 入口；只能由运维显式调用（{@code java -cp … InitialAdministratorCommand}）。
 */
public class InitialAdministratorCommand {

    /** 内置管理员角色代码。 */
    public static final String ADMIN_ROLE_CODE = "ADMIN";

    /** 引导互斥锁名称：只用于串行化首次初始化，不承载业务语义。 */
    public static final String BOOTSTRAP_LOCK_NAME = "yumi:platform:initial-administrator";

    /** 读取初始口令的环境变量名。 */
    public static final String PASSWORD_ENV = "YUMI_INITIAL_ADMIN_PASSWORD";

    /** 读取初始登录名的环境变量名。 */
    public static final String LOGIN_NAME_ENV = "YUMI_INITIAL_ADMIN_LOGIN";

    private final IdentityApplicationService identity;

    private final RoleRepository roles;

    private final JdbcClient jdbc;

    private final DataSource dataSource;

    private final TransactionTemplate transactionTemplate;

    private final Clock clock;

    public InitialAdministratorCommand(
            IdentityApplicationService identity,
            RoleRepository roles,
            JdbcClient jdbc,
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.identity = Objects.requireNonNull(identity, "身份服务不能为空");
        this.roles = Objects.requireNonNull(roles, "角色仓储不能为空");
        this.jdbc = Objects.requireNonNull(jdbc, "JdbcClient 不能为空");
        this.dataSource = Objects.requireNonNull(dataSource, "数据源不能为空");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "事务管理器不能为空"));
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
    }

    /**
     * 创建首个管理员。
     *
     * @param loginName 登录名（通常来自 {@code YUMI_INITIAL_ADMIN_LOGIN}）
     * @param rawPassword 初始口令（通常来自 {@code YUMI_INITIAL_ADMIN_PASSWORD} 或交互输入）
     * @return 管理员账号引用；返回值不含口令
     */
    public AccountRef initialize(String loginName, String rawPassword) {
        if (loginName == null || loginName.isBlank()) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_LOGIN_NAME", "必须提供初始管理员登录名");
        }
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_PASSWORD", "必须提供初始管理员口令");
        }

        AccountRef account;
        // 命名锁在独立连接上获取：MySQL 的 GET_LOCK 是连接级的，且必须在事务建立快照之前生效。
        try (Connection connection = dataSource.getConnection()) {
            boolean locked = acquireLock(connection);
            if (!locked) {
                throw new ApiException(
                        ApiErrorType.CONCURRENT_CONFLICT,
                        "BOOTSTRAP_IN_PROGRESS",
                        "另一个初始化正在进行，请稍后重试");
            }
            try {
                account = transactionTemplate.execute(status -> {
                    boolean occupied = jdbc.sql("SELECT EXISTS (SELECT 1 FROM platform_accounts)")
                            .query(Boolean.class)
                            .single();
                    if (occupied) {
                        throw new ApiException(
                                ApiErrorType.BUSINESS_REJECTION,
                                "SYSTEM_NOT_EMPTY",
                                "系统已存在账号，拒绝重新初始化管理员权限");
                    }
                    ensureAdminRole();
                    AccountRef created = identity.createAccount(
                            new com.yumi.platform.api.identity.CreateAccountCommand(
                                    loginName, rawPassword, "系统管理员"));
                    roles.replaceAccountRoles(
                            created.accountBusinessId().value(), Set.of(ADMIN_ROLE_CODE),
                            clock.instant(), created.accountBusinessId().value());
                    return created;
                });
            } finally {
                releaseLock(connection);
            }
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("无法取得数据库连接以执行首次初始化", exception);
        }

        Objects.requireNonNull(account, "初始化事务必须返回账号");
        return account;
    }

    private static boolean acquireLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 10)")) {
            statement.setString(1, BOOTSTRAP_LOCK_NAME);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 1;
            }
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("无法取得初始化互斥锁", exception);
        }
    }

    private static void releaseLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, BOOTSTRAP_LOCK_NAME);
            statement.executeQuery().close();
        } catch (java.sql.SQLException exception) {
            // 释放失败不改变已完成的结果；连接关闭时 MySQL 自动释放命名锁
        }
    }

    /** 注册内置管理员角色及一期全部权限点；已存在则保持不变。 */
    void ensureAdminRole() {
        for (String permissionCode : com.yumi.platform.api.authorization.PermissionCodes.all()) {
            roles.upsertPermission(permissionCode, permissionCode, "GLOBAL");
        }
        if (roles.findByRoleCode(ADMIN_ROLE_CODE).isPresent()) {
            return;
        }
        roles.insert(new RoleRecord(
                0L,
                StableBusinessId.of("ROLE-" + ADMIN_ROLE_CODE).value(),
                ADMIN_ROLE_CODE,
                "系统管理员",
                "ACTIVE",
                0L,
                com.yumi.platform.api.authorization.PermissionCodes.all(),
                clock.instant()));
    }

    /**
     * 命令行入口：显式运行才会执行。
     *
     * <p>口令优先从环境变量读取；缺失时交互读取且不回显。任何日志都不记录口令。
     */
    public static void main(String[] args) {
        String loginName = System.getenv(LOGIN_NAME_ENV);
        if (loginName == null || loginName.isBlank()) {
            loginName = args.length > 0 ? args[0] : null;
        }
        String password = System.getenv(PASSWORD_ENV);
        if (password == null || password.isBlank()) {
            Console console = System.console();
            if (console == null) {
                System.err.println("无法读取口令：请设置 " + PASSWORD_ENV + " 或使用交互终端");
                System.exit(2);
                return;
            }
            char[] entered = console.readPassword("请输入初始管理员口令（不回显）：");
            password = entered == null ? null : new String(entered);
            java.util.Arrays.fill(entered, '\0');
        }

        var context = org.springframework.boot.SpringApplication.run(
                com.yumi.YumiApplication.class, "--spring.main.web-application-type=none");
        try {
            var command = context.getBean(InitialAdministratorCommand.class);
            AccountRef account = command.initialize(loginName, password);
            System.out.println("已创建初始管理员：" + account.loginName()
                    + "（accountBusinessId=" + account.accountBusinessId().value() + "）");
        } finally {
            password = null;
            context.close();
        }
    }
}

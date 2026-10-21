package com.yumi.platform.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.file.FilePolicy;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.authorization.application.PermissionService;
import com.yumi.platform.authorization.application.RoleAdministrationService;
import com.yumi.platform.authorization.application.port.RoleRepository;
import com.yumi.platform.aftercommit.application.AfterCommitTaskRepository;
import com.yumi.platform.aftercommit.application.AfterCommitTaskService;
import com.yumi.platform.audit.application.AuditService;
import com.yumi.platform.file.application.FileApplicationService;
import com.yumi.platform.file.application.port.FileContentStore;
import com.yumi.platform.file.application.port.FileRepository;
import com.yumi.platform.file.infrastructure.FileSystemContentStore;
import com.yumi.platform.idempotency.application.PersistentIdempotentCommandService;
import com.yumi.platform.identity.application.AccountAdministrationService;
import com.yumi.platform.identity.application.IdentityApplicationService;
import com.yumi.platform.identity.application.port.AccountRepository;
import com.yumi.platform.identity.application.port.PasswordHasher;
import com.yumi.platform.identity.application.port.SessionRepository;
import com.yumi.platform.identity.application.port.TokenHasher;
import com.yumi.platform.identity.bootstrap.InitialAdministratorCommand;
import com.yumi.platform.identity.infrastructure.SessionAuthenticationFilter;
import com.yumi.platform.numbering.application.BusinessNumberService;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 平台模块装配。
 *
 * <p>应用服务与端口在此显式装配：业务模块只注入公开接口，不依赖具体实现类；平台内部的
 * Repository 与端口实现由基础设施层提供，不跨模块暴露。
 *
 * <p>口令与会话参数属于部署配置，从环境变量读取并给出安全默认值；仓库中不写任何默认口令。
 */
@Configuration
public class PlatformConfiguration {

    @Bean
    public Clock platformClock() {
        return Clock.systemUTC();
    }

    @Bean
    public PasswordHasher passwordHasher() {
        return new PasswordHasher() {
            private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

            @Override
            public String hash(String rawPassword) {
                return encoder.encode(rawPassword);
            }

            @Override
            public boolean matches(String rawPassword, String passwordHash) {
                return passwordHash != null && encoder.matches(rawPassword, passwordHash);
            }
        };
    }

    @Bean
    public TokenHasher tokenHasher() {
        return new TokenHasher() {
            @Override
            public String hash(String rawToken) {
                try {
                    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                    return java.util.HexFormat.of().formatHex(
                            digest.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                } catch (java.security.NoSuchAlgorithmException exception) {
                    throw new IllegalStateException("缺少 SHA-256 算法", exception);
                }
            }
        };
    }

    @Bean
    public IdentityApplicationService identityApplicationService(
            AccountRepository accounts,
            SessionRepository sessions,
            RoleRepository roles,
            PasswordHasher passwordHasher,
            TokenHasher tokenHasher,
            Clock clock) {
        return new IdentityApplicationService(accounts, sessions, roles, passwordHasher, tokenHasher, clock);
    }

    @Bean
    public CurrentActorProvider currentActorProvider() {
        return new SessionAuthenticationFilter.HolderBasedActorProvider();
    }

    @Bean
    public AuthorizationService authorizationService(
            CurrentActorProvider currentActorProvider, RoleRepository roles) {
        return new PermissionService(currentActorProvider, roles);
    }

    @Bean
    public AccountAdministrationService accountAdministrationService(
            IdentityApplicationService identity,
            RoleRepository roles,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            AuditService audit,
            Clock clock) {
        return new AccountAdministrationService(
                identity, roles, authorization, currentActorProvider, audit, clock);
    }

    @Bean
    public RoleAdministrationService roleAdministrationService(
            RoleRepository roles,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            Clock clock,
            AuditService audit) {
        return new RoleAdministrationService(roles, authorization, currentActorProvider, clock, audit);
    }

    @Bean
    public PersistentIdempotentCommandService idempotentCommandService(JdbcClient jdbc) {
        return new PersistentIdempotentCommandService(jdbc);
    }

    @Bean
    public com.yumi.platform.idempotency.application.PlatformCommandService platformCommandService(
            PersistentIdempotentCommandService idempotency,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        return new com.yumi.platform.idempotency.application.PlatformCommandService(
                idempotency, transactionManager);
    }

    @Bean
    public AuditService auditService(JdbcClient jdbc, ObjectMapper objectMapper) {
        return new AuditService(jdbc, objectMapper);
    }

    @Bean
    public BusinessNumberService businessNumberService(JdbcClient jdbc) {
        return new BusinessNumberService(jdbc);
    }

    @Bean
    public FilePolicy filePolicy(
            @Value("${yumi.file.max-size-bytes:10485760}") long maxSizeBytes,
            @Value("${yumi.file.allowed-media-types:image/png,image/jpeg,image/webp,application/pdf}")
            String allowedMediaTypes) {
        java.util.Set<String> types = java.util.Arrays.stream(allowedMediaTypes.split(","))
                .map(String::strip)
                .filter(type -> !type.isEmpty())
                .map(type -> type.toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new FilePolicy(maxSizeBytes, types);
    }

    @Bean
    public FileContentStore fileContentStore(@Value("${yumi.file.root:${java.io.tmpdir}/yumi-files}") String root) {
        return new FileSystemContentStore(Path.of(root));
    }

    @Bean
    public FileApplicationService fileApplicationService(
            FileRepository files,
            FileContentStore contentStore,
            FilePolicy policy,
            AuthorizationService authorization,
            CurrentActorProvider currentActorProvider,
            Clock clock) {
        return new FileApplicationService(
                files, contentStore, policy, authorization, currentActorProvider, clock);
    }

    @Bean
    public AfterCommitTaskService afterCommitTaskService(
            AfterCommitTaskRepository tasks, ObjectMapper objectMapper, Clock clock) {
        return new AfterCommitTaskService(tasks, objectMapper, clock);
    }

    @Bean
    public InitialAdministratorCommand initialAdministratorCommand(
            IdentityApplicationService identity,
            RoleRepository roles,
            JdbcClient jdbc,
            javax.sql.DataSource dataSource,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        return new InitialAdministratorCommand(identity, roles, jdbc, dataSource, transactionManager, clock);
    }

    @Bean
    public SessionAuthenticationFilter sessionAuthenticationFilter(
            com.yumi.platform.identity.infrastructure.IdentityActorResolver identity) {
        return new SessionAuthenticationFilter(identity);
    }
}

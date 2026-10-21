package com.yumi.platform.identity.api.http;

import com.yumi.platform.api.http.HttpHeaders;
import com.yumi.platform.api.http.RequestContext;
import com.yumi.platform.api.identity.AccountRef;
import com.yumi.platform.api.identity.AccountStatus;
import com.yumi.platform.api.identity.AccountView;
import com.yumi.platform.api.identity.CreateAccountCommand;
import com.yumi.platform.api.identity.CurrentActor;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.identity.LoginCommand;
import com.yumi.platform.api.identity.LogoutCommand;
import com.yumi.platform.api.identity.SessionRef;
import com.yumi.platform.http.HttpRequestContextResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 身份 HTTP 适配器（交付清单 P01）。
 *
 * <p>只调用平台公开命令与 Query，不接触 Repository。登录与退出不需要幂等键（登录本身无业务写入，
 * 重复登录会新建会话）；账号创建与状态变更属于正式命令，必须携带 {@code Idempotency-Key}。
 *
 * <p>不提供匿名管理员注册路由；首个管理员只能由运维显式运行 {@code InitialAdministratorCommand}。
 */
@RestController
@RequestMapping("/api/platform/identity")
public class IdentityController {

    private final com.yumi.platform.identity.application.IdentityApplicationService identity;

    private final com.yumi.platform.identity.application.AccountAdministrationService accounts;

    private final CurrentActorProvider currentActorProvider;

    private final HttpRequestContextResolver contextResolver;

    private final com.yumi.platform.idempotency.application.PlatformCommandService commands;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public IdentityController(
            com.yumi.platform.identity.application.IdentityApplicationService identity,
            com.yumi.platform.identity.application.AccountAdministrationService accounts,
            CurrentActorProvider currentActorProvider,
            HttpRequestContextResolver contextResolver,
            com.yumi.platform.idempotency.application.PlatformCommandService commands,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.identity = identity;
        this.accounts = accounts;
        this.currentActorProvider = currentActorProvider;
        this.contextResolver = contextResolver;
        this.commands = commands;
        this.objectMapper = objectMapper;
    }

    /** 登录请求体。 */
    public record LoginRequest(String loginName, String password) {
    }

    /** 登录响应：令牌只在此处返回一次。 */
    public record LoginResponse(
            String token, String sessionBusinessId, String accountBusinessId, Instant expiresAt) {
    }

    /** 当前账号响应。 */
    public record CurrentAccountResponse(
            String accountBusinessId,
            String loginName,
            String sessionBusinessId,
            List<String> permissions) {
    }

    /** 创建账号请求体；不含任何员工字段。 */
    public record CreateAccountRequest(String loginName, String initialPassword, String displayName) {
    }

    /** 变更状态请求体。 */
    public record ChangeStatusRequest(String targetStatus, long expectedRowVersion) {
    }

    /** 账号响应。 */
    public record AccountResponse(
            String accountBusinessId, String loginName, String status, long rowVersion) {
    }

    /** 账号列表项。 */
    public record AccountViewResponse(
            String accountBusinessId,
            String loginName,
            String displayName,
            String status,
            long rowVersion,
            List<String> roleCodes,
            Instant createdAt) {
    }

    @PostMapping("/sessions")
    public LoginResponse login(@RequestBody LoginRequest request) {
        SessionRef session = identity.login(new LoginCommand(request.loginName(), request.password()));
        return new LoginResponse(
                session.token(),
                session.sessionBusinessId().value(),
                session.accountBusinessId().value(),
                session.expiresAt());
    }

    @PostMapping("/sessions/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String token = authorization == null ? "" : authorization.replaceFirst("^Bearer ", "");
        identity.logout(new LogoutCommand(token));
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @GetMapping("/me")
    public CurrentAccountResponse me() {
        CurrentActor actor = currentActorProvider.requireCurrentActor();
        return new CurrentAccountResponse(
                actor.accountBusinessId().value(),
                actor.loginName(),
                actor.sessionBusinessId().value(),
                actor.permissions().stream().sorted().toList());
    }

    @PostMapping("/accounts")
    public ResponseEntity<AccountResponse> createAccount(
            @RequestBody CreateAccountRequest request,
            HttpServletRequest servletRequest) {
        RequestContext context = contextResolver.resolveCommand(servletRequest);
        var execution = commands.execute(
                new com.yumi.platform.api.idempotency.CommandRequest(
                        "platform.identity.createAccount",
                        context.idempotencyKey(),
                        com.yumi.platform.idempotency.application.PlatformCommandService.fingerprint(
                                objectMapper, request)),
                () -> {
                    AccountRef account = accounts.createAccount(new CreateAccountCommand(
                            request.loginName(), request.initialPassword(), request.displayName()));
                    return new com.yumi.platform.api.idempotency.CommandOutcome(
                            "ACCOUNT", account.accountBusinessId().value());
                });

        AccountView view = identity.getAccount(
                com.yumi.platform.api.value.StableBusinessId.of(execution.outcome().resultBusinessId()));
        return ResponseEntity.status(execution.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(HttpHeaders.REQUEST_ID, context.requestId())
                .body(new AccountResponse(
                        view.accountBusinessId().value(), view.loginName(),
                        view.status().name(), view.rowVersion()));
    }

    @PostMapping("/accounts/{accountBusinessId}/status")
    public AccountResponse changeStatus(
            @PathVariable String accountBusinessId,
            @RequestBody ChangeStatusRequest request,
            HttpServletRequest servletRequest) {
        RequestContext context = contextResolver.resolveCommand(servletRequest);
        var execution = commands.execute(
                new com.yumi.platform.api.idempotency.CommandRequest(
                        "platform.identity.changeAccountStatus",
                        context.idempotencyKey(),
                        com.yumi.platform.idempotency.application.PlatformCommandService.fingerprint(
                                objectMapper, java.util.Map.of(
                                        "accountBusinessId", accountBusinessId,
                                        "targetStatus", request.targetStatus(),
                                        "expectedRowVersion", request.expectedRowVersion()))),
                () -> {
                    AccountRef account = accounts.changeAccountStatus(
                            new com.yumi.platform.api.identity.ChangeAccountStatusCommand(
                                    com.yumi.platform.api.value.StableBusinessId.of(accountBusinessId),
                                    AccountStatus.valueOf(request.targetStatus()),
                                    request.expectedRowVersion()));
                    return new com.yumi.platform.api.idempotency.CommandOutcome(
                            "ACCOUNT", account.accountBusinessId().value());
                });

        AccountView view = identity.getAccount(
                com.yumi.platform.api.value.StableBusinessId.of(execution.outcome().resultBusinessId()));
        return new AccountResponse(
                view.accountBusinessId().value(), view.loginName(),
                view.status().name(), view.rowVersion());
    }

    @GetMapping("/accounts")
    public List<AccountViewResponse> listAccounts() {
        return accounts.listAccounts().stream().map(IdentityController::toResponse).toList();
    }

    @GetMapping("/accounts/{accountBusinessId}")
    public AccountViewResponse getAccount(@PathVariable String accountBusinessId) {
        AccountView view = identity.getAccount(
                com.yumi.platform.api.value.StableBusinessId.of(accountBusinessId));
        return toResponse(view);
    }

    private static AccountViewResponse toResponse(AccountView view) {
        return new AccountViewResponse(
                view.accountBusinessId().value(),
                view.loginName(),
                view.displayName(),
                view.status().name(),
                view.rowVersion(),
                view.roleCodes().stream().sorted().toList(),
                view.createdAt());
    }
}

package com.yumi.platform.api.identity;

/**
 * 身份命令：登录、退出、账号创建与状态变更。
 *
 * <p>账号创建与状态变更需要 {@code platform.account.manage} 权限并写入审计；这些约束由
 * 应用服务在事务内校验，HTTP 适配层只负责请求映射。
 */
public interface IdentityApplicationService {

    SessionRef login(LoginCommand command);

    void logout(LogoutCommand command);

    AccountRef createAccount(CreateAccountCommand command);

    AccountRef changeAccountStatus(ChangeAccountStatusCommand command);
}

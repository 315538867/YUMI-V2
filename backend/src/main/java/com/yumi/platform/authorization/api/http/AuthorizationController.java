package com.yumi.platform.authorization.api.http;

import com.yumi.platform.api.authorization.AssignAccountRolesCommand;
import com.yumi.platform.api.authorization.RoleRef;
import com.yumi.platform.api.authorization.RoleView;
import com.yumi.platform.api.authorization.SaveRoleCommand;
import com.yumi.platform.api.http.HttpHeaders;
import com.yumi.platform.api.http.RequestContext;
import com.yumi.platform.authorization.application.RoleAdministrationService;
import com.yumi.platform.http.HttpRequestContextResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 角色与账号授权 HTTP 适配器（交付清单 P02）。
 *
 * <p>只调用角色管理公开命令与 Query；账号授权使用完整集合覆盖，不提供增量接口。
 */
@RestController
@RequestMapping("/api/platform/authorization")
public class AuthorizationController {

    private final RoleAdministrationService roles;

    private final HttpRequestContextResolver contextResolver;

    private final com.yumi.platform.idempotency.application.PlatformCommandService commands;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public AuthorizationController(
            RoleAdministrationService roles,
            HttpRequestContextResolver contextResolver,
            com.yumi.platform.idempotency.application.PlatformCommandService commands,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.roles = roles;
        this.contextResolver = contextResolver;
        this.commands = commands;
        this.objectMapper = objectMapper;
    }

    /** 保存角色请求体；新建时 {@code roleBusinessId} 为空，{@code expectedRowVersion} 为 0。 */
    public record SaveRoleRequest(
            String roleBusinessId, String roleCode, String name, Set<String> permissionCodes,
            long expectedRowVersion) {
    }

    /** 账号授权请求体。 */
    public record AssignRolesRequest(Set<String> roleCodes, long expectedAccountRowVersion) {
    }

    /** 角色响应。 */
    public record RoleResponse(
            String roleBusinessId, String roleCode, String name, String status,
            List<String> permissionCodes, long rowVersion) {
    }

    @GetMapping("/roles")
    public List<RoleResponse> listRoles() {
        return roles.listRoles().stream().map(AuthorizationController::toResponse).toList();
    }

    @GetMapping("/roles/{roleBusinessId}")
    public RoleResponse getRole(@PathVariable String roleBusinessId) {
        return toResponse(roles.getRole(roleBusinessId));
    }

    @PostMapping("/roles")
    public ResponseEntity<RoleResponse> createRole(
            @RequestBody SaveRoleRequest request, HttpServletRequest servletRequest) {
        var context = contextResolver.resolveCommand(servletRequest);
        var execution = commands.execute(
                new com.yumi.platform.api.idempotency.CommandRequest(
                        "platform.authorization.createRole",
                        context.idempotencyKey(),
                        com.yumi.platform.idempotency.application.PlatformCommandService.fingerprint(
                                objectMapper, request)),
                () -> {
                    RoleRef created = roles.saveRole(new SaveRoleCommand(
                            null, request.roleCode(), request.name(), request.permissionCodes(), 0L));
                    return new com.yumi.platform.api.idempotency.CommandOutcome(
                            "ROLE", created.roleBusinessId().value());
                });
        RoleView view = roles.getRole(execution.outcome().resultBusinessId());
        return ResponseEntity.status(execution.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(toResponse(view));
    }

    @PutMapping("/roles/{roleBusinessId}")
    public RoleResponse updateRole(
            @PathVariable String roleBusinessId,
            @RequestBody SaveRoleRequest request,
            HttpServletRequest servletRequest) {
        var context = contextResolver.resolveCommand(servletRequest);
        var execution = commands.execute(
                new com.yumi.platform.api.idempotency.CommandRequest(
                        "platform.authorization.updateRole",
                        context.idempotencyKey(),
                        com.yumi.platform.idempotency.application.PlatformCommandService.fingerprint(
                                objectMapper, request)),
                () -> {
                    RoleRef updated = roles.saveRole(new SaveRoleCommand(
                            roleBusinessId, request.roleCode(), request.name(),
                            request.permissionCodes(), request.expectedRowVersion()));
                    return new com.yumi.platform.api.idempotency.CommandOutcome(
                            "ROLE", updated.roleBusinessId().value());
                });
        return toResponse(roles.getRole(execution.outcome().resultBusinessId()));
    }

    @PutMapping("/accounts/{accountBusinessId}/roles")
    public ResponseEntity<Void> assignRoles(
            @PathVariable String accountBusinessId,
            @RequestBody AssignRolesRequest request,
            HttpServletRequest servletRequest) {
        var context = contextResolver.resolveCommand(servletRequest);
        commands.execute(
                new com.yumi.platform.api.idempotency.CommandRequest(
                        "platform.authorization.assignAccountRoles",
                        context.idempotencyKey(),
                        com.yumi.platform.idempotency.application.PlatformCommandService.fingerprint(
                                objectMapper, java.util.Map.of(
                                        "accountBusinessId", accountBusinessId,
                                        "roleCodes", request.roleCodes().stream().sorted().toList(),
                                        "expectedAccountRowVersion", request.expectedAccountRowVersion()))),
                () -> {
                    roles.assignAccountRoles(new AssignAccountRolesCommand(
                            accountBusinessId, request.roleCodes(), request.expectedAccountRowVersion()));
                    return new com.yumi.platform.api.idempotency.CommandOutcome(
                            "ACCOUNT", accountBusinessId);
                });
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @GetMapping("/accounts/{accountBusinessId}/permissions")
    public List<String> permissionsOfAccount(@PathVariable String accountBusinessId) {
        return roles.permissionsOfAccount(accountBusinessId).stream().sorted().toList();
    }

    @GetMapping("/permissions")
    public List<String> knownPermissions() {
        return com.yumi.platform.api.authorization.PermissionCodes.all().stream().sorted().toList();
    }

    private static RoleResponse toResponse(RoleView view) {
        return new RoleResponse(
                view.roleBusinessId(), view.roleCode(), view.name(), view.status(),
                view.permissionCodes().stream().sorted().toList(), view.rowVersion());
    }

    private static RoleResponse toResponse(RoleRef role) {
        return new RoleResponse(
                role.roleBusinessId().value(), role.roleCode(), null, "ACTIVE",
                role.permissionCodes().stream().sorted().toList(), role.rowVersion());
    }
}

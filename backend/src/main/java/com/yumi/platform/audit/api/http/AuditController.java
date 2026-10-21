package com.yumi.platform.audit.api.http;

import com.yumi.platform.api.audit.AuditEntry;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.audit.application.AuditService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计检索 HTTP 适配器（交付清单 P03）。
 *
 * <p>只读检索；不开放任何修改或删除审计的接口。错误补充通过追加关联说明实现，
 * 需要 {@code platform.audit.read} 权限。
 */
@RestController
@RequestMapping("/api/platform/audit")
public class AuditController {

    private final AuditService audit;

    private final AuthorizationService authorization;

    public AuditController(AuditService audit, AuthorizationService authorization) {
        this.audit = audit;
        this.authorization = authorization;
    }

    /** 审计响应。 */
    public record AuditResponse(
            String auditBusinessId,
            String requestId,
            String accountBusinessId,
            String moduleCode,
            String actionCode,
            String targetType,
            String targetBusinessId,
            String resultCode,
            String noteOfAuditBusinessId,
            Map<String, String> detail,
            Instant occurredAt) {
    }

    /** 追加补充说明请求体。 */
    public record AppendNoteRequest(String note) {
    }

    @GetMapping
    public List<AuditResponse> findByTarget(
            @RequestParam String targetType,
            @RequestParam String targetBusinessId,
            @RequestParam(defaultValue = "50") int limit) {
        requireAuditRead();
        return audit.findByTarget(targetType, targetBusinessId, limit).stream()
                .map(AuditController::toResponse)
                .toList();
    }

    @GetMapping("/requests/{requestId}")
    public List<AuditResponse> findByRequestId(@PathVariable String requestId) {
        requireAuditRead();
        return audit.findByRequestId(requestId).stream().map(AuditController::toResponse).toList();
    }

    @PostMapping("/{auditBusinessId}/notes")
    public Map<String, String> appendNote(
            @PathVariable String auditBusinessId, @RequestBody AppendNoteRequest request) {
        requireAuditRead();
        String noteId = audit.appendNote(auditBusinessId, request.note(), Instant.now());
        return Map.of("noteAuditBusinessId", noteId);
    }

    private void requireAuditRead() {
        try {
            authorization.require(PermissionCodes.PLATFORM_AUDIT_READ, ResourceScope.GLOBAL);
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ApiException(
                    ApiErrorType.FORBIDDEN, "PERMISSION_DENIED", "当前账号没有读取审计的权限");
        }
    }

    private static AuditResponse toResponse(AuditEntry entry) {
        return new AuditResponse(
                entry.auditBusinessId(),
                entry.requestId(),
                entry.accountBusinessId(),
                entry.moduleCode(),
                entry.actionCode(),
                entry.targetType(),
                entry.targetBusinessId(),
                entry.resultCode(),
                entry.noteOfAuditBusinessId(),
                entry.detail(),
                entry.occurredAt());
    }
}

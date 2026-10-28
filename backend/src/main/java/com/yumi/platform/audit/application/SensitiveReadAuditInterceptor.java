package com.yumi.platform.audit.application;

import com.yumi.platform.api.audit.AuditService;
import com.yumi.platform.api.audit.RecordAuditCommand;
import com.yumi.platform.api.http.RequestCorrelationHolder;
import com.yumi.platform.api.identity.CurrentActorProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

/** 记录工资、成本、现金和报表等敏感数据的读取访问。 */
public class SensitiveReadAuditInterceptor implements HandlerInterceptor {

    private static final Set<String> SENSITIVE_PREFIXES = Set.of(
            "/api/payroll", "/api/finance/cash", "/api/finance/costs",
            "/api/reports", "/api/reporting", "/api/after-sales/costs");

    private final AuditService audit;
    private final CurrentActorProvider currentActor;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public SensitiveReadAuditInterceptor(
            AuditService audit,
            CurrentActorProvider currentActor,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.audit = audit;
        this.currentActor = currentActor;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (!"GET".equalsIgnoreCase(request.getMethod()) || response.getStatus() >= 400) {
            return;
        }
        String path = request.getRequestURI();
        if (SENSITIVE_PREFIXES.stream().noneMatch(path::startsWith)) {
            return;
        }
        String actor;
        try {
            actor = currentActor.requireCurrentActor().accountBusinessId().value();
        } catch (RuntimeException exception) {
            return;
        }
        String module = path.startsWith("/api/payroll") || path.startsWith("/api/finance")
                ? "finance" : path.startsWith("/api/after-sales") ? "orders" : "reporting";
        String targetType = module + "_READ";
        String targetId = path.length() > 160 ? path.substring(path.length() - 160) : path;
        transactionTemplate.executeWithoutResult(status -> audit.record(new RecordAuditCommand(
                RequestCorrelationHolder.getOrUnknown(), actor, module, "READ_SENSITIVE",
                targetType, targetId, "SUCCESS", Map.of("path", path), clock.instant())));
    }
}

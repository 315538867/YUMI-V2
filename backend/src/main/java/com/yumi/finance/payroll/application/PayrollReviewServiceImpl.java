package com.yumi.finance.payroll.application;

import com.yumi.finance.payroll.api.PayrollReviewRef;
import com.yumi.finance.payroll.api.PayrollReviewService;
import com.yumi.finance.payroll.api.ReviewPayrollCommand;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.idempotency.CommandOutcome;
import com.yumi.platform.api.idempotency.CommandRequest;
import com.yumi.platform.api.idempotency.IdempotentCommandService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 已核定工资只读复核实现。 */
public class PayrollReviewServiceImpl implements PayrollReviewService {

    private static final String SCOPE = "finance.payroll.review";

    private final PayrollApplicationServiceImpl calculation;
    private final PayrollRepository payroll;
    private final IdempotentCommandService idempotency;
    private final AuthorizationService authorization;
    private final TransactionTemplate transactionTemplate;

    public PayrollReviewServiceImpl(
            PayrollApplicationServiceImpl calculation,
            PayrollRepository payroll,
            IdempotentCommandService idempotency,
            AuthorizationService authorization,
            PlatformTransactionManager transactionManager) {
        this.calculation = calculation;
        this.payroll = payroll;
        this.idempotency = idempotency;
        this.authorization = authorization;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public PayrollReviewRef review(ReviewPayrollCommand command) {
        authorization.require(PermissionCodes.PAYROLL_SENSITIVE_READ, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_REVIEW", "工资复核命令不能为空");
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(
                        SCOPE, command.idempotencyKey(), sha256(command.resultBusinessId())),
                () -> {
                    PayrollReviewRef ref = calculation.runReview(command.resultBusinessId());
                    return new CommandOutcome("PAYROLL_REVIEW", ref.reviewRunBusinessId());
                }).outcome());
        var run = payroll.findRun(outcome.resultBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "PAYROLL_REVIEW_MISSING", "复核运行无法读取"));
        int lineCount = payroll.listReferenceLines(run.runBusinessId()).size();
        return new PayrollReviewRef(
                command.resultBusinessId(), run.runBusinessId(), run.fingerprint(), lineCount);
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法", exception);
        }
    }
}

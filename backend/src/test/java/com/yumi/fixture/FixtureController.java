package com.yumi.fixture;

import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ErrorCode;
import com.yumi.shared.error.ApiFieldError;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 契约测试专用固定端点：承载尚未落地业务命令的错误码与幂等行为。
 * 仅供 HTTP 黑盒测试引用，不进入生产源码。
 */
@RestController
@RequestMapping("/api/__test/fixture")
public class FixtureController {

    private static final AtomicInteger FLAKY_ATTEMPTS = new AtomicInteger();

    private final JdbcTemplate jdbcTemplate;
    private final com.yumi.identity.AuditContext auditContext;

    public FixtureController(JdbcTemplate jdbcTemplate, com.yumi.identity.AuditContext auditContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditContext = auditContext;
    }

    public record NameBody(@NotBlank String name) {
    }

    public record TokenBody(String token) {
    }

    @PostMapping("/validated")
    public String validated(@Valid @RequestBody NameBody body) {
        return "ok";
    }

    @GetMapping("/state")
    public void state() {
        throw new ApiException(ErrorCode.STATE_NOT_EDITABLE);
    }

    @GetMapping("/quantity")
    public void quantity() {
        throw new ApiException(ErrorCode.QUANTITY_BELOW_SHIPPED, ErrorCode.QUANTITY_BELOW_SHIPPED.defaultMessage(),
                List.of(new ApiFieldError("quantity", "新数量不得低于累计有效发货")));
    }

    @GetMapping("/source")
    public void source() {
        throw new ApiException(ErrorCode.SOURCE_ALREADY_CONSUMED);
    }

    @GetMapping("/finance")
    public void finance() {
        throw new ApiException(ErrorCode.PAYMENT_DRAFT_FORBIDDEN);
    }

    @GetMapping("/conflict")
    public void conflict() {
        throw new ObjectOptimisticLockingFailureException("fixture", "1");
    }

    @GetMapping("/missing")
    public void missing() {
        throw new ApiException(ErrorCode.ORDER_NOT_FOUND);
    }

    /** 写命令抛业务冲突：验证 409 结果落幂等库并对同键同请求重放。 */
    @PostMapping("/conflict-write")
    public void conflictWrite(@RequestBody(required = false) Object body) {
        throw new ApiException(ErrorCode.STATE_NOT_EDITABLE);
    }

    /**
     * 幂等事实写入：每次真正执行都会落一行并返回新 token，用于区分重放与重复执行。
     */
    @PostMapping("/counter")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenBody counter(@RequestBody(required = false) Object body) {
        var token = UUID.randomUUID().toString();
        insertFact(token);
        return new TokenBody(token);
    }

    /** 事务内先写事实再失败：验证失败事务回滚不留成功事实，但保留安全审计。 */
    @PostMapping("/counter-then-fail")
    @org.springframework.transaction.annotation.Transactional
    public TokenBody counterThenFail(@RequestBody(required = false) Object body) {
        insertFact(UUID.randomUUID().toString());
        throw new IllegalStateException("business transaction failed after fact write");
    }

    private void insertFact(String token) {
        jdbcTemplate.update(
                "INSERT INTO number_sequences (sequence_key, current_value, version, created_at, updated_at) "
                        + "VALUES (?, 0, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                "fixture:" + token);
    }

    /** 返回当前审计上下文，验证操作人/请求 ID/幂等键/服务端时间可被写命令读取。 */
    @GetMapping("/audit-context")
    public com.yumi.identity.AuditContext.Context auditContext() {
        return auditContext.current();
    }

    /** 首次调用抛 500，之后成功：证明 5xx 不落幂等记录、可用同键重试。 */
    @PostMapping("/flaky")
    public TokenBody flaky(@RequestBody(required = false) Object body) {
        if (FLAKY_ATTEMPTS.getAndIncrement() == 0) {
            throw new IllegalStateException("first call fails");
        }
        return new TokenBody("flaky-ok");
    }

    public static void resetFlaky() {
        FLAKY_ATTEMPTS.set(0);
    }
}

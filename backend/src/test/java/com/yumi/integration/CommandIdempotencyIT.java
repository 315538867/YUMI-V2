package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.platform.api.audit.AuditEntry;
import com.yumi.platform.api.audit.RecordAuditCommand;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.idempotency.CommandExecution;
import com.yumi.platform.api.idempotency.CommandOutcome;
import com.yumi.platform.api.idempotency.CommandRequest;
import com.yumi.platform.api.numbering.BusinessNumberService;
import com.yumi.platform.audit.application.AuditService;
import com.yumi.platform.idempotency.application.PersistentIdempotentCommandService;
import com.yumi.support.AbstractMySqlIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 持久幂等、并发编号与审计（PL-02、PL-03、SF-03）。
 *
 * <p>规则来源：`docs/一期/05-跨模块接口与事务规则.md` §9、`06-平台公共能力模块.md` §9、§12。
 * 使用真实 MySQL 验证：唯一键裁决并发、成功记录与业务同事务、失败不留下成功结果、审计不可修改。
 */
class CommandIdempotencyIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private PersistentIdempotentCommandService idempotency;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        idempotency = new PersistentIdempotentCommandService(jdbc);
        transactionTemplate = new TransactionTemplate(transactionManager);
        jdbc.sql("DELETE FROM platform_probe_business_facts").update();
        jdbc.sql("DELETE FROM platform_idempotency_records").update();
        jdbc.sql("DELETE FROM platform_business_sequences").update();
        jdbc.sql("DELETE FROM platform_audit_logs").update();
    }

    @Test
    @DisplayName("相同幂等键与输入只执行一次命令体，重试返回原结果")
    void sameKeyAndInputReplaysOriginalResult() {
        CommandRequest request = request("orders.trade.confirm", "idem-1", "payload-a");
        AtomicInteger executions = new AtomicInteger();

        CommandExecution first = executeInTransaction(request, () -> {
            executions.incrementAndGet();
            insertProbeFact("FACT-1");
            return new CommandOutcome("ORDER", "ORD-1");
        });
        CommandExecution second = executeInTransaction(request, () -> {
            executions.incrementAndGet();
            insertProbeFact("FACT-2");
            return new CommandOutcome("ORDER", "ORD-2");
        });

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.outcome()).isEqualTo(first.outcome());
        assertThat(executions).hasValue(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM platform_probe_business_facts").query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("相同幂等键不同输入返回 IDEMPOTENCY_CONFLICT，且不产生新事实")
    void sameKeyDifferentInputIsRejected() {
        executeInTransaction(request("orders.trade.confirm", "idem-2", "payload-a"), () -> {
            insertProbeFact("FACT-1");
            return new CommandOutcome("ORDER", "ORD-1");
        });

        assertThatThrownBy(() -> executeInTransaction(
                request("orders.trade.confirm", "idem-2", "payload-b"),
                () -> {
                    insertProbeFact("FACT-2");
                    return new CommandOutcome("ORDER", "ORD-2");
                }))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).type())
                        .isEqualTo(ApiErrorType.IDEMPOTENCY_CONFLICT));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM platform_probe_business_facts").query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("命令体失败时整笔回滚：不留下成功幂等记录，也不留下业务事实")
    void failedCommandLeavesNoSuccessRecord() {
        CommandRequest request = request("orders.trade.confirm", "idem-3", "payload-a");

        assertThatThrownBy(() -> executeInTransaction(request, () -> {
            insertProbeFact("FACT-1");
            throw new IllegalStateException("模拟业务失败");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM platform_idempotency_records").query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM platform_probe_business_facts").query(Long.class).single())
                .isZero();

        // 失败后重试可以真正执行
        CommandExecution retry = executeInTransaction(request, () -> {
            insertProbeFact("FACT-1");
            return new CommandOutcome("ORDER", "ORD-1");
        });
        assertThat(retry.replayed()).isFalse();
        assertThat(retry.outcome().resultBusinessId()).isEqualTo("ORD-1");
    }

    @Test
    @DisplayName("并发相同幂等键只产生一份成功业务结果")
    void concurrentSameKeyProducesSingleResult() throws Exception {
        CommandRequest request = request("orders.trade.confirm", "idem-4", "payload-a");
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Callable<CommandExecution>> tasks = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            tasks.add(() -> {
                start.await();
                try {
                    return executeInTransaction(request, () -> {
                        insertProbeFact("FACT-CONCURRENT");
                        return new CommandOutcome("ORDER", "ORD-1");
                    });
                } catch (ApiException inProgress) {
                    // 处理中冲突可接受：客户端应查询最终状态，而不是重复执行业务
                    assertThat(inProgress.code()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
                    return null;
                }
            });
        }

        List<Future<CommandExecution>> futures = tasks.stream().map(executor::submit).toList();
        start.countDown();
        int succeeded = 0;
        for (Future<CommandExecution> future : futures) {
            CommandExecution execution = future.get(60, TimeUnit.SECONDS);
            if (execution != null && !execution.replayed()) {
                succeeded++;
            }
        }
        executor.shutdownNow();

        assertThat(succeeded).isLessThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM platform_probe_business_facts").query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM platform_idempotency_records WHERE status = 'SUCCEEDED'")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("不同命令范围使用相同幂等键互不影响")
    void scopeSeparatesIdempotencyKeys() {
        executeInTransaction(request("orders.trade.confirm", "shared-key", "payload-a"), () -> {
            insertProbeFact("FACT-ORDER");
            return new CommandOutcome("ORDER", "ORD-1");
        });
        CommandExecution other = executeInTransaction(
                request("production.task.arrange", "shared-key", "payload-b"), () -> {
                    insertProbeFact("FACT-TASK");
                    return new CommandOutcome("TASK", "TASK-1");
                });

        assertThat(other.replayed()).isFalse();
        assertThat(other.outcome().resultType()).isEqualTo("TASK");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM platform_probe_business_facts").query(Long.class).single())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("并发编号唯一且连续，事务回滚时编号一并回退")
    void numberingIsUniqueUnderConcurrency() throws Exception {
        BusinessNumberService numbering = new com.yumi.platform.numbering.application.BusinessNumberService(jdbc);
        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            tasks.add(() -> {
                start.await();
                return new TransactionTemplate(transactionManager).execute(status ->
                        numbering.nextNumber("ORD-2026", "ORD-2026-", 6));
            });
        }

        List<Future<String>> futures = tasks.stream().map(executor::submit).toList();
        start.countDown();
        List<String> numbers = new ArrayList<>();
        for (Future<String> future : futures) {
            numbers.add(future.get(60, TimeUnit.SECONDS));
        }
        executor.shutdownNow();

        assertThat(numbers).doesNotHaveDuplicates();
        assertThat(numbers).allMatch(number -> number.matches("ORD-2026-\\d{6}"));
    }

    @Test
    @DisplayName("业务事务回滚时编号不推进，避免空洞编号被误认为已使用")
    void numberingRollsBackWithTransaction() {
        BusinessNumberService numbering = new com.yumi.platform.numbering.application.BusinessNumberService(jdbc);
        String first = transactionTemplate.execute(status -> numbering.nextNumber("ORD-2026", "ORD-2026-", 6));
        assertThat(first).isEqualTo("ORD-2026-000001");

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            numbering.nextNumber("ORD-2026", "ORD-2026-", 6);
            throw new IllegalStateException("模拟业务失败");
        })).isInstanceOf(IllegalStateException.class);

        String next = transactionTemplate.execute(status -> numbering.nextNumber("ORD-2026", "ORD-2026-", 6));
        assertThat(next).isEqualTo("ORD-2026-000002");
    }

    @Test
    @DisplayName("审计与业务同事务：业务回滚不留下成功审计")
    void auditRollsBackWithBusiness() {
        AuditService audit = new AuditService(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            audit.record(auditCommand("req-1", "CREATE", "ORD-1"));
            throw new IllegalStateException("模拟业务失败");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM platform_audit_logs").query(Long.class).single()).isZero();

        transactionTemplate.execute(status -> audit.record(auditCommand("req-2", "CREATE", "ORD-1")));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM platform_audit_logs").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("审计不可修改：敏感字段被遮蔽，且只允许追加补充说明")
    void auditIsImmutableAndRedacted() {
        AuditService audit = new AuditService(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        String auditBusinessId = transactionTemplate.execute(status -> audit.record(
                new RecordAuditCommand(
                        "req-3", "ACC-1", "platform.identity", "LOGIN", "ACCOUNT", "ACC-1", "SUCCESS",
                        Map.of("password", "plain-secret", "token", "raw-token", "note", "visible"),
                        Instant.parse("2026-10-20T02:00:00Z"))));

        AuditEntry entry = transactionTemplate.execute(status ->
                audit.findByRequestId("req-3").getFirst());
        assertThat(entry.detail().get("password")).isEqualTo("***");
        assertThat(entry.detail().get("token")).isEqualTo("***");
        assertThat(entry.detail().get("note")).isEqualTo("visible");

        String noteId = transactionTemplate.execute(status ->
                audit.appendNote(auditBusinessId, "补充：操作者为临时账号", Instant.parse("2026-10-20T03:00:00Z")));
        List<AuditEntry> all = transactionTemplate.execute(status -> audit.findByRequestId("req-3"));
        assertThat(all).hasSize(2);
        assertThat(all).anyMatch(item -> item.auditBusinessId().equals(noteId)
                && noteId.equals(item.auditBusinessId())
                && auditBusinessId.equals(item.noteOfAuditBusinessId()));

        // 原始记录内容未被覆盖
        assertThat(all.stream().filter(item -> item.auditBusinessId().equals(auditBusinessId))
                .findFirst().orElseThrow().detail().get("note")).isEqualTo("visible");
    }

    private CommandExecution executeInTransaction(CommandRequest request, java.util.function.Supplier<CommandOutcome> body) {
        return transactionTemplate.execute(status -> idempotency.execute(request, body));
    }

    private void insertProbeFact(String businessId) {
        jdbc.sql("""
                        INSERT INTO platform_probe_business_facts (fact_business_id, created_at)
                        VALUES (:businessId, UTC_TIMESTAMP(6))
                        """)
                .param("businessId", businessId + "-" + System.nanoTime())
                .update();
    }

    private RecordAuditCommand auditCommand(String requestId, String action, String targetId) {
        return new RecordAuditCommand(
                requestId, "ACC-1", "orders.trade", action, "ORDER", targetId, "SUCCESS", Map.of(),
                Instant.parse("2026-10-20T02:00:00Z"));
    }

    private static CommandRequest request(String scope, String key, String payload) {
        return new CommandRequest(scope, key, sha256(payload));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
